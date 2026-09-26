# LAYER 3 — BACKEND APPLICATION SERVICES
## AI-Assisted Legal Metrology Testing & Compliance Platform
### Builds on: LAYER1_DATA_DOMAIN_MODEL.md, LAYER2_RULES_ENGINE.md

---

## 0. What This Layer Is

This is the Spring Boot (Java 21) application layer that sits between the Client Layer (React) and everything below it (Layer 1 data, Layer 2 rules engine, and — via a clearly separated path — the RAG/AI layer). It owns:

- Authentication & authorization (RBAC across Engineer/Reviewer/Admin/Viewer)
- All CRUD and workflow logic for manufacturers, instruments, test sessions, and observations
- Orchestrating the Layer 2 rules engine (the sequence described in Layer 2 §7)
- Report generation and the hash-chain/QR pipeline
- Audit trail writing
- Dashboard/analytics read APIs
- Regulatory version management (which `rule_version` is active, amendment handling)
- The public verification API (unauthenticated QR-scan endpoint)

**Critical boundary this layer must enforce:** every endpoint here is either a *deterministic* path (touches Layer 2, produces a legal PASS/FAIL) or a *conversational/explanatory* path (touches the RAG index, produces text for a human to read). No endpoint should do both, and the deterministic path must never call an LLM. See the RAG-vs-hardcoded note above — this layer is where that boundary is physically implemented as separate services and separate endpoints.

---

## 1. Service Breakdown

```
com.legalmetrology.backend
├── auth              — AuthService, RBAC filters, JWT issuing/validation
├── manufacturer       — ManufacturerService (CRUD)
├── instrument          — InstrumentService (CRUD, class/e/d validation)
├── testsession          — TestSessionService (workflow: DRAFT→SUBMITTED→REVIEWED→APPROVED/REJECTED)
├── observation           — ObservationService (create/lock observations)
├── evaluation              — EvaluationOrchestrationService (calls rulesengine module, Layer 2)
├── report                   — ReportGenerationService, HashChainService, QRService
├── verification               — PublicVerificationService (unauthenticated)
├── audit                       — AuditLogService (append-only writer, used by every service above)
├── ruleversion                  — RuleVersionService (regulatory amendment management)
├── analytics                     — DashboardService (read-only aggregates)
├── driftdetection                  — PredictiveDriftService (uses historical calculation data)
├── whatif                          — WhatIfSimulationService (re-runs rules engine hypothetically)
└── regulatoryqa                     — RegulatoryQAService (thin proxy to the RAG/AI layer — Layer 4)
```

---

## 2. Auth & RBAC

### 2.1 Endpoints
```
POST /api/auth/login          → { email, password } → { accessToken, refreshToken, roles[] }
POST /api/auth/refresh        → { refreshToken } → { accessToken }
POST /api/auth/logout
```

### 2.2 Enforcement
- Spring Security filter chain validates JWT on every request except `/api/verify/**` (public) and `/api/auth/**`.
- Role checks via `@PreAuthorize("hasRole('ENGINEER')")` etc. on controller methods — do not scatter role logic inline in service code; keep it declarative at the controller boundary so it's auditable at a glance.
- Map roles → allowed actions explicitly:

| Action | ENGINEER | REVIEWER | ADMIN | VIEWER |
|---|---|---|---|---|
| Create instrument | ✓ | – | ✓ | – |
| Record observations | ✓ | – | ✓ | – |
| Submit test session | ✓ | – | ✓ | – |
| Approve/reject test session | – | ✓ | ✓ | – |
| Manage rule_version / mpe_rule | – | – | ✓ | – |
| View reports/dashboard | ✓ | ✓ | ✓ | ✓ |
| Generate final report | – | ✓ | ✓ | – |

---

## 3. Manufacturer & Instrument Service

```
POST   /api/manufacturers                → create
GET    /api/manufacturers/{id}
GET    /api/manufacturers?search=...

POST   /api/instruments                  → create (validates class_id/e/d/Max/Min per Layer 1 §1.1–1.2 before insert)
GET    /api/instruments/{id}
GET    /api/instruments?manufacturerId=&status=&class=
PATCH  /api/instruments/{id}/status      → REGISTERED/UNDER_TEST/VERIFIED/REJECTED/DECOMMISSIONED
POST   /api/instruments/{id}/photo       → upload to object storage, store object_key
```

**Validation logic that belongs here (not the rules engine, not the DB constraints alone):**
- `n_intervals = Max/e` falls within the declared `accuracy_class`'s `min_n`/`max_n` range (Layer 1 §1.1) — reject instrument creation if not, with a clear error naming which OIML class boundary was violated. This is business validation on *instrument setup*, distinct from Layer 2's per-test tolerance evaluation.
- `d_value <= e_value` — DB constraint (Layer 1) is the backstop; this service should catch it earlier with a friendly error message.

---

## 4. Test Session & Observation Service

### 4.1 Workflow state machine
```
DRAFT → SUBMITTED → REVIEWED → APPROVED
                          └──→ REJECTED
```
- `DRAFT`: engineer creating/editing observations freely (`observation.is_locked = FALSE`)
- `SUBMITTED`: engineer submits → all observations for the session are locked (`is_locked = TRUE`, enforced in this service, not left to the client) → triggers evaluation orchestration (§5)
- `REVIEWED`/`APPROVED`/`REJECTED`: reviewer/admin action; only these roles can transition out of `SUBMITTED`

### 4.2 Endpoints
```
POST   /api/test-sessions                        → create (DRAFT), pins rule_version_id to the currently active version (§8)
POST   /api/test-sessions/{id}/observations       → add observation (rejected if session not DRAFT)
PUT    /api/test-sessions/{id}/observations/{obsId}  → edit (rejected if locked)
POST   /api/test-sessions/{id}/submit             → locks observations, triggers evaluation (§5), status → SUBMITTED
POST   /api/test-sessions/{id}/review             → { verdict: REVIEWED|REJECTED, comments } — REVIEWER/ADMIN only
GET    /api/test-sessions/{id}                    → full detail incl. observations + calculations + compliance_result
GET    /api/test-sessions?instrumentId=&status=
```

**Completeness check before allowing submit:** re-use Layer 2 §3.1's point about incomplete load series — this service must validate the expected observation set exists (all required load points / corners / trial counts for the declared `test_type` and procedures present) *before* calling submit, and return a specific error naming what's missing, rather than letting an incomplete session reach the rules engine.

---

## 5. Evaluation Orchestration Service

This is the direct implementation of the sequence specified in Layer 2 §7. It is the **only** service allowed to call the Layer 2 `RulesEngine` interface.

```java
@Transactional
public void evaluate(UUID testSessionId) {
    TestSession session = load(testSessionId);
    InstrumentSpec spec = mapToInstrumentSpec(session.getInstrument());
    List<Observation> observations = observationRepo.findBySession(testSessionId);

    List<CalculationOutput> results = new ArrayList<>();
    for (Observation obs : observations) {
        MpeLookupResult mpe = mpeRuleRepo.resolve(
            session.getRuleVersionId(), spec.accuracyClassSymbol(),
            obs.getAppliedLoad(), spec.eValue(), session.getTestType());
        CalculationOutput out = rulesEngine.evaluateObservation(
            spec, mapToObservationInput(obs), mpe, session.getTestType());
        results.add(out);
        calculationRepo.save(out);                 // → calculation table
        complianceResultRepo.saveObservationLevel(out); // → compliance_result, scope=OBSERVATION_LEVEL
    }

    TestRollupResult rollup = rulesEngine.rollUpTest(results);
    complianceResultRepo.saveTestLevel(testSessionId, rollup);
    auditLogService.record(testSessionId, "TEST_SESSION", "EVALUATE", rollup);
    session.setStatus(rollup.verdict().equals("PASS") ? "REVIEWED" : "REVIEWED"); // still needs reviewer sign-off regardless of verdict — evaluation ≠ approval
}
```

**Important distinction to keep visible in the code and in any demo:** the rules engine producing a `FAIL` does **not** auto-reject the test session. It still goes to `REVIEWED` and waits for a human Reviewer to formally approve/reject — the engine informs the decision, a licensed reviewer makes it. This mirrors how legal metrology actually works and matters if this is presented to judges/evaluators.

---

## 6. Report Generation, Hash Chain & QR

```
POST /api/test-sessions/{id}/report        → REVIEWER/ADMIN only, only if status = APPROVED
     1. Render report content (PDF via a template engine — e.g. iText or a HTML→PDF pipeline; DOCX via Apache POI if both formats required)
     2. Canonicalize report content (fixed field order, fixed formatting) → SHA-256 → content_hash
     3. Fetch prev_hash = content_hash of the most recently generated report (chain continuity)
     4. Generate a random opaque qr_token (not the report's UUID — don't leak sequential IDs)
     5. Persist `report` + `hash_chain_entry` in one transaction (Layer 1 §5)
     6. Generate QR image encoding a URL like https://<domain>/verify/{qr_token}
     7. Upload PDF/DOCX to object storage, store object_key
GET  /api/reports/{id}
GET  /api/reports/{id}/download?format=pdf|docx
```

**Why canonicalize before hashing:** if the PDF byte layout changes slightly between generations (timestamps, font hinting) but the *content* is identical, the hash would still change and break verification. Hash the structured content (JSON of report fields), not the rendered PDF bytes.

---

## 7. Public Verification API (unauthenticated, mobile-first — matches the Public Verification Layer in the architecture doc)

```
GET /api/verify/{qr_token}
    → { reportNo, instrumentModel, manufacturer, testDate, verdict, isFinal, contentHashPrefix, chainIntact: boolean }
```
- No login, minimal data exposed (no PII beyond what's needed to confirm authenticity — no owner address, no engineer's personal details)
- `chainIntact` is computed by re-walking `prev_hash` links back through `hash_chain_entry` and confirming no gaps — this is what makes tampering detectable, not just the single hash
- Rate-limit this endpoint (public, unauthenticated — needs abuse protection) at the API gateway/infra level, out of scope for this layer's code but flag it as a deployment requirement

---

## 8. Regulatory Version Management

```
POST /api/rule-versions                    → ADMIN only, create a new rule_version (e.g. after an amendment is notified)
POST /api/rule-versions/{id}/mpe-rules      → bulk-load the MPE table rows for that version
PATCH /api/rule-versions/{id}/activate      → sets effective_to = now() on the previously active version, this one becomes active
GET  /api/rule-versions
GET  /api/rule-versions/{id}/diff/{otherId} → structured diff of MPE tables between two versions (feeds the "Amendment Diff" feature in the architecture doc)
```
New `test_session`s always pin to whichever `rule_version` has `effective_to IS NULL` at creation time (Layer 1 §5) — this endpoint set is how that pinned value gets set correctly, and how historical reports stay reproducible even after an amendment changes the active table.

---

## 9. Regulatory Q&A Service (the RAG boundary, explicit)

```
POST /api/regulatory-qa/ask
     { question: "What is the tolerance for a Class III instrument at half capacity?" }
     → { answer: string, citedClauses: [...], confidence: "explanatory-only" }
```
This endpoint is a **thin proxy** to the Layer 4 AI/RAG service — it does not touch `mpe_rule`, does not call the rules engine, and its response must be visibly labeled as explanatory (not a compliance determination) in both the API contract and the UI that consumes it. This is the structural enforcement of the boundary described at the top of this document — mixing this endpoint's logic into `EvaluationOrchestrationService` (§5) would be the single most important thing to avoid in this layer.

---

## 10. Audit Trail Service

Every service above (§3–§9) calls `auditLogService.record(entityType, entityId, action, diff)` on every mutating operation — this is a cross-cutting concern, best implemented as a Spring AOP aspect (`@Auditable` annotation on service methods) rather than manual calls sprinkled everywhere, so it can't be forgotten on a new endpoint.

---

## 11. Dashboard & Analytics (read-only)

```
GET /api/analytics/summary            → counts by status, pass/fail rate, tests this month
GET /api/analytics/instruments/{id}/history   → all test_sessions + verdicts for one instrument over time
GET /api/analytics/drift/{instrumentId}       → feeds PredictiveDriftService (§12)
```
All read-only, all backed by indexed queries against Layer 1 tables — no writes, no rules-engine calls. Fine to denormalize here with materialized views for performance (Layer 1 §5 explicitly allows this at the reporting layer, just not in the core schema).

---

## 12. Predictive Drift Detection & What-If Simulator (stretch features — build last)

- **Drift detection**: looks at an instrument's `calculation.error_value` trend across its `test_session` history — a simple linear-regression-on-error-over-time is enough for a hackathon demo; flag instruments trending toward MPE before they actually fail. This reads historical data only, writes nothing, never influences a live compliance verdict.
- **What-if simulator**: lets a user hypothetically adjust an observation's indicated_value and see the rules engine's result *without persisting it* — call `rulesEngine.evaluateObservation()` directly with ad hoc input, skip the orchestration service's DB writes entirely. This is the one place outside `EvaluationOrchestrationService` allowed to call the Layer 2 engine directly, precisely because it's explicitly non-persistent and clearly labeled hypothetical in the UI.

---

## 13. What Layer 3 Explicitly Does Not Include

- No rules-engine logic itself (Layer 2 owns every tolerance/MPE calculation)
- No RAG pipeline internals — chunking, embedding, retrieval logic live in Layer 4; this layer only proxies to it (§9)
- No frontend rendering (Client Layer consumes these REST APIs)
- No PDF ingestion/extraction pipeline for building `mpe_rule` seed data — that's a one-time offline data-prep task (see the RAG-vs-hardcoded note above), not a runtime service

---

## 14. Next Layer

**Layer 4 — AI/RAG Layer** (Python + FastAPI): owns the regulatory embedding pipeline (chunking the Act/General Rules/OIML R76 PDFs into pgvector), the retrieval + LLM explanation service that Layer 3's `RegulatoryQAService` proxies to, historical-report RAG (semantic search over past reports), amendment-diff explanation in natural language, vision/OCR for instrument nameplate reading, and multilingual support — all explicitly downstream of and non-authoritative over the deterministic verdict this layer and Layer 2 already produced.
