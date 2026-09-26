# AI-Assisted Legal Metrology Testing & Compliance Platform
## MASTER DOCUMENT — Full Architecture, Modules & Point-to-Point Implementation Plan

---

## 1. Project Summary

**One-line definition:**
> Build a digital Legal Metrology testing and compliance platform that records NAWI test observations, deterministically evaluates them against applicable OIML R 76 requirements, uses RAG to retrieve and explain regulatory evidence, learns from historical test reports, and automatically generates verifiable standardized reports — with predictive, tamper-proof, and public-verification differentiators layered on top.

**Explicitly NOT:**
- A generic RAG chatbot
- A system where an LLM decides PASS/FAIL
- A "PDF → embeddings → LLM → PASS/FAIL" pipeline

---

## 2. Background

Under the **Legal Metrology Act, 2009** and **Legal Metrology (General) Rules, 2011**, NAWIs (electronic scales, platform scales, weighbridges) used in trade, healthcare, agriculture and industry must be model-approved, verified, and stamped before use, per **OIML R 76**.

Currently this is manual (spreadsheets, Word templates), causing slow preparation, calculation errors, inconsistent reports, and poor historical record-keeping.

---

## 3. Core Workflow

```
Instrument Registration → Test Data Entry → Validation →
Deterministic OIML R 76 Rules Engine → Automatic Calculations →
PASS / FAIL → Regulatory RAG Explanation → Test Report Generation →
Report Repository → QR Verification
```

---

## 4. Three-Layer Architecture

### Layer 1 — Deterministic Rules Engine
No LLM computes or hallucinates compliance numbers.
```
Instrument Characteristics → Accuracy Class → Capacity → Scale Interval →
Applicable Rules → Test Observation → Calculation → Permissible Error →
Comparison → PASS / FAIL
```

### Layer 2 — Regulatory RAG
```
Documents → PDF/Text extraction → Cleaning → Chunking → Metadata →
Embeddings → Vector Database → Retriever
```
Every answer returns: **Answer + Source document + Section/clause + Page + Version**.

### Layer 3 — LLM (explanation only)
```
Test Result → Rules Engine → FAIL → Retrieve regulation → RAG → LLM → Explanation
```

---

## 5. Core Modules (14)

| # | Module | Purpose |
|---|--------|---------|
| 1 | Auth & RBAC | Login, roles: Administrator, Test Engineer, Reviewer, Viewer |
| 2 | Manufacturer & Instrument Management | Registration of manufacturers, instruments, models |
| 3 | Test Case & Observation Entry | Digital forms per OIML R 76 test |
| 4 | Validation Module | Pre-calculation data validation |
| 5 | Deterministic Rules Engine | OIML R76 formulas, error calc, PASS/FAIL |
| 6 | Regulatory RAG Module | Retrieval over Act/Rules/Amendments/OIML with citations |
| 7 | Historical Report RAG Module | Semantic search over past reports |
| 8 | AI Assistant / LLM Orchestration | Query router + explanation layer |
| 9 | Report Generation Module | PDF & DOCX standardized reports |
| 10 | QR Verification Module | QR generation & verification API |
| 11 | Report Repository & Search | Relational storage, instrument-wise history |
| 12 | Dashboard & Analytics | Testing stats, PASS/FAIL trends |
| 13 | Audit Trail Module | Immutable action logging |
| 14 | Regulatory Version Management | Admin control of rule/document versions |

---

## 6. Query Router

```
User → AI Assistant → Query Router
          ┌──────────┼──────────┐
          ↓          ↓          ↓
      Database     Rules      RAG (Regulatory + Historical)
       Query       Engine
          └──────────┼──────────┘
                     ↓
                    LLM → Final Answer
```

| Query | Routes to |
|---|---|
| "Show all failed ABC-1000 reports." | PostgreSQL |
| "Why did this test fail?" | Rules Engine + Regulatory RAG + LLM |
| "What does the relevant regulation require?" | Regulatory RAG |
| "Have similar models failed this test?" | Historical Report RAG |

---

## 7. Example End-to-End Scenario

Manufacturer: ABC Weighing Systems Pvt. Ltd. | Model: ABC-1000 | Capacity: 1000 kg | Class: III | Test ID: NAWI-2026-00125

| Applied Load | Indicated Value |
|---|---|
| 100 kg | 100.2 kg |
| 200 kg | 200.1 kg |
| 500 kg | 500.8 kg |
| 1000 kg | 1001.5 kg |

System computes Observed Error → Permissible Error → Requirement → PASS/FAIL. Engineer asks "Why did it fail?" → AI retrieves clause + explains. Then: `Generate Report → PDF + DOCX → Store → Generate QR`.

---

## 8. Technology Stack

| Layer | Stack |
|---|---|
| Frontend | React, Vite, Tailwind CSS, shadcn/ui |
| Backend | Java 21, Spring Boot, Spring Security, REST APIs |
| Database | PostgreSQL |
| AI/RAG service | Python, FastAPI, Sentence Transformers, Vector DB |
| Vector DB | pgvector (preferred) / Qdrant / Chroma / Weaviate |
| Document processing | PDF, DOCX, OCR for scanned docs |
| Report generation | PDF, DOCX, template-based |
| QR | Unique QR tied to secure verification ID |

---

## 9. Database Entities

```
users, roles, laboratories, manufacturers, instruments, instrument_models,
test_cases, test_procedures, observations, calculations, compliance_results,
attachments, reports, report_versions, audit_logs, standard_versions,
regulatory_documents
```
```
Manufacturer → Instrument Model → Test Case → Test Procedures →
Observations → Calculations → Compliance Results → Report
```

---

## 10. Versioned Rules

```
Rules Engine: OIML R76 v.A → v.B → Future
Regulatory KB: Act 2009 → Rules 2011 → Amendment 2019 → 2025 → 2026
```
Every test records which rule version was applicable at test time.

---

## 11. Security (RBAC)

| Role | Permissions |
|---|---|
| Administrator | Manage users, labs, regulatory versions, config |
| Test Engineer | Register instruments, create tests, enter observations, upload evidence, draft reports |
| Reviewer | Review calculations/results, approve/reject |
| Viewer | Search, view, download approved reports |

---

## 12. Audit Trail

```
Report created → Observation entered → Observation modified →
Calculation executed → Report generated → Reviewer approved → Report locked
```
Fields: `user, timestamp, action, old value, new value`.

---

## 13. Dashboard

Total Instruments Tested, Tests In Progress, Completed, Passed/Failed, Reports Generated. Charts: tests over time, PASS/FAIL distribution, instruments by manufacturer, common failure categories, pending reviews, reports by model.

---

# 14. UNIQUENESS / DIFFERENTIATOR FEATURES — Point-to-Point Implementation

This is the section that separates the project from a "RAG chatbot for Legal Metrology." Each feature below includes **what it does, why it's novel, data/architecture needed, step-by-step implementation, and how it plugs into the existing modules**.

---

### 14.1 Predictive Drift Detection

**What it does:** Analyzes an instrument's historical observation data across multiple past tests to detect measurement drift trends and flags instruments statistically likely to fail their *next* verification before it happens.

**Why it's novel:** Most teams only use historical data for lookup/search. This makes historical data *predictive*, turning the platform proactive rather than reactive.

**Architecture:**
```
Historical Observations (per instrument, per test type)
        ↓
Feature Extraction (error trend, rate of change, environmental factors)
        ↓
Drift Model (simple regression / time-series, e.g. linear trend of observed error over time)
        ↓
Risk Score (Low / Medium / High)
        ↓
Dashboard Flag + Alert
```

**Step-by-step implementation:**
1. Extend `observations` and `compliance_results` tables to always store a timestamp + observed error, keyed by `instrument_id` + `test_case_id`.
2. Build a scheduled batch job (Python service) that, per instrument, pulls the last N test results for the same test type.
3. Fit a simple linear regression (or moving average of error growth) on observed error vs. time. This does **not** need deep learning — a lightweight statistical model is sufficient and easily explainable (important for a compliance product; avoid a black-box model here).
4. Compute a **risk score** = projected error at next scheduled test date vs. permissible error threshold.
5. Surface as a "Predicted Risk" column in the Dashboard and Instrument Detail page, with a plain-language explanation generated by the LLM layer (e.g., "Observed error has increased ~0.3kg per test over the last 3 verifications; projected to approach the Class III tolerance limit by [date].")
6. Never let this drive an automatic FAIL — it's advisory only, reviewed by a human (ties into the Human-in-the-loop principle).

**Effort:** Medium. Requires enough historical data to be meaningful — good for Phase 2, after MVP.

---

### 14.2 Tamper-Evident Report Chain

**What it does:** Cryptographically hash-chains every report version so tampering with any past report is mathematically detectable — without needing blockchain infrastructure.

**Why it's novel:** Turns "immutable audit trail" from a claim into a provable, demoable guarantee — critical for a compliance/legal product.

**Architecture:**
```
Report v1 → hash(v1)
Report v2 → hash(v2 + hash(v1))
Report v3 → hash(v3 + hash(v2))
```

**Step-by-step implementation:**
1. Add a `report_hash` and `previous_hash` column to `report_versions`.
2. On every report generation/approval event, compute `SHA-256(report_content + previous_hash)` and store it.
3. Build a `/verify-chain/{reportId}` backend endpoint that recomputes the chain from the first version and confirms no link was altered.
4. Expose a "Verify Integrity" button in the Report Detail UI — clicking it re-runs the check live and shows a green/red result.
5. Optionally anchor the final hash of a locked report externally (e.g., timestamped in a public log) for extra tamper-evidence — not required for MVP, mention as a future extension.

**Effort:** Low. Pure backend logic, no new infra — excellent effort-to-impact ratio for a hackathon.

---

### 14.3 Regulatory "What-If" Impact Simulator

**What it does:** When a new regulatory amendment is added to the knowledge base, automatically re-evaluates past test results against the new rule version and flags which historical PASS results would now be borderline or FAIL.

**Why it's novel:** Makes "version-aware compliance" concrete and demoable rather than just an architectural claim — this is the single strongest proof-of-concept feature for judges.

**Architecture:**
```
New Amendment Ingested → Rules Engine (new version) →
Re-run stored Observations (unchanged) → Compare old vs new Result →
Impact Report (instruments/reports affected)
```

**Step-by-step implementation:**
1. Ensure the Rules Engine is versioned (already planned in Section 10) — each rule set has an `effective_from` date and is stored as data (JSON/decision table), not hardcoded.
2. Build an admin action: "Simulate Impact of New Rule Version."
3. Backend pulls all `observations` + `compliance_results` for tests that fall under the affected rule scope (e.g., same accuracy class/test type).
4. Re-runs the deterministic Rules Engine using the new rule version's formulas/thresholds against the *stored raw observations* (never re-collect data — only re-evaluate).
5. Produces a diff report: `Report ID | Old Result | New Result | Delta`.
6. Surface this as an "Impact Analysis" screen — this is a great live demo moment: "here's what happens to our historical PASS reports if this amendment applies."

**Effort:** Medium-High — depends on Rules Engine being cleanly version-abstracted from day one. Prioritize designing the Rules Engine this way even in MVP so this feature is a Phase 2 add-on, not a rewrite.

---

### 14.4 Amendment Diff Summarizer

**What it does:** LLM-generated plain-language summary of what actually changed between two versions of a regulation, grounded in citations from both versions.

**Why it's novel:** Directly useful to real Legal Metrology officers, and builds naturally on the existing Regulatory RAG — low additional infra cost for a good feature.

**Architecture:**
```
Regulatory Doc v_old + v_new → Text diff (structural) →
Relevant chunks from both retrieved via RAG →
LLM summarization (grounded, cited) → "What Changed" card
```

**Step-by-step implementation:**
1. When a new amendment is ingested, tag it with `supersedes_document_id` linking to the prior version.
2. Run a structural text diff (e.g., section-by-section comparison) to identify changed clauses.
3. For each changed clause, retrieve both the old and new clause text via the Regulatory RAG.
4. Prompt the LLM: "Summarize what changed between these two clauses in plain language, citing both versions" — LLM only explains, retrieval supplies the actual text (keeps this Layer 3, not Layer 1/2 responsibility).
5. Display as a "What Changed in [Amendment 2026]" panel on the document version page.

**Effort:** Low-Medium. Reuses existing RAG infrastructure almost entirely.

---

### 14.5 Public-Facing QR Verification (Consumer Protection)

**What it does:** Extends QR verification beyond B2B (lab-to-manufacturer) so any consumer can scan a shop's weighing scale QR sticker and see certification status, last verification date, and result — no login required.

**Why it's novel:** Reframes the whole project from an internal lab tool into consumer-protection infrastructure, which is the Act's actual stated purpose — this materially strengthens the pitch's social-impact narrative.

**Architecture:**
```
Physical QR sticker on instrument → Public scan (no auth) →
GET /public/verify/{certId} → Read-only, minimal-data response →
"VALID – Verified [date], Class III" or "INVALID / EXPIRED"
```

**Step-by-step implementation:**
1. Add a separate **unauthenticated** public endpoint (distinct from the internal report API) that only exposes minimal, non-sensitive fields: instrument model, verification status, verification date, validity period.
2. Rate-limit this endpoint to prevent scraping/abuse.
3. Design a simple mobile-friendly public verification page (no app install needed — QR opens a web page).
4. Print/generate the QR at the time a report is locked (ties into Module 10), tied to a certificate ID, not the internal report ID (avoid exposing internal identifiers).
5. In the demo, physically show scanning a "shop scale" QR with a phone camera to pull up live verification — a strong, tangible closing beat for the pitch.

**Effort:** Low. Mostly a scoped-down read API + a simple public page; big narrative payoff for small effort.

---

### 14.6 Manufacturer/Model Quality Scorecard

**What it does:** Aggregates PASS/FAIL history by manufacturer and model across all labs on the platform, surfacing which manufacturers have recurring compliance issues.

**Why it's novel:** A regulator-facing feature (useful to the Legal Metrology Department itself, not just individual labs) — broadens the pitch's stakeholders and shows platform-level, not just single-lab, value.

**Architecture:**
```
compliance_results (all labs) → Aggregate by manufacturer_id / model_id →
Failure rate, common failure test types → Scorecard view
```

**Step-by-step implementation:**
1. Build an aggregation query/materialized view: pass rate, fail rate, and top failing test types per manufacturer and per model.
2. Add a "Manufacturer Scorecard" dashboard page (Module 12 extension) — filterable by date range, accuracy class.
3. Highlight manufacturers/models with failure rates above a configurable threshold.
4. This view is naturally restricted to Administrator/Reviewer roles (ties into Module 1 RBAC) since it's a regulatory oversight feature.

**Effort:** Low. Pure SQL aggregation + a dashboard view on data you're already storing.

---

### 14.7 Vision-Assisted Evidence Capture

**What it does:** Uses a vision model to cross-check an uploaded instrument photo's nameplate/serial number against the registered instrument record, and flag visible physical damage.

**Why it's novel:** Adds a multi-modal layer most RAG-only competitors will not attempt — shows technical range beyond text-based RAG.

**Architecture:**
```
Uploaded Photo → Vision Model (OCR on nameplate + damage classification) →
Compare extracted serial# vs registered serial# → Match/Mismatch flag
```

**Step-by-step implementation:**
1. On photo upload (existing "photographs/supporting documents" feature), send the image to a vision-capable model or OCR service.
2. Extract nameplate text (serial number, model number) via OCR.
3. Compare extracted values against the `instruments` table record; flag mismatches for reviewer attention.
4. Optionally run a basic image classification pass for visible damage/corrosion keywords, surfaced as a soft warning, not a blocking check.
5. Keep this advisory-only (like drift detection) — a Reviewer always makes the final call.

**Effort:** Medium. Depends on OCR/vision API availability; scope it to nameplate OCR only if time-constrained, skip damage detection for MVP.

---

### 14.8 Click-to-Source Citations

**What it does:** Regulatory RAG citations are clickable and jump directly to the highlighted clause inside the source PDF, instead of being plain text references.

**Why it's novel:** Small UX investment, disproportionately impressive in a live demo — makes "explainable compliance" tangible rather than something judges have to take on faith.

**Architecture:**
```
RAG chunk metadata (doc_id, page_number, bounding_box/char_offset) →
Frontend PDF viewer → Scroll-to + highlight on citation click
```

**Step-by-step implementation:**
1. During document ingestion (Regulatory RAG pipeline), store page number and, if possible, character offset or bounding box per chunk alongside the embedding metadata (this is a small addition to the existing chunking pipeline in Section 4/Layer 2).
2. Use a frontend PDF viewer library (e.g., `pdf.js`) capable of programmatic scroll-to-page and text highlighting.
3. When the LLM/RAG response includes a citation, render it as a clickable pill; on click, open the PDF viewer scrolled to that page with the clause highlighted.
4. This is a frontend + metadata-only change — no new backend service required.

**Effort:** Low-Medium. Worth prioritizing since it visibly upgrades every existing RAG answer in the demo.

---

### 14.9 Hindi / Regional Language Support

**What it does:** AI Assistant (Layer 3) supports Hindi (and ideally one more regional language) for both questions and generated explanations.

**Why it's novel:** Legal Metrology is a nationwide system used across diverse states; most competing teams will build English-only tools — this is a genuine accessibility differentiator.

**Architecture:**
```
User query (Hindi/regional) → Translate to English (or use multilingual embedding model) →
Regulatory RAG (English source docs) → LLM response generation in original language
```

**Step-by-step implementation:**
1. Use a multilingual embedding model (or translate the query to English before retrieval) so the Regulatory RAG can still match against English-only source documents.
2. Prompt the LLM to respond in the same language the user asked in, while keeping citations to the original English clause (with an inline translated snippet).
3. Add a language selector in the AI Assistant UI (Module 8).
4. For MVP, English + Hindi is sufficient to demonstrate the capability; do not attempt full document translation of the regulatory corpus itself — only the conversational layer.

**Effort:** Low-Medium if using an off-the-shelf multilingual LLM; mostly a prompting and UI change.

---

## 15. Priority Recommendation for the Pitch

If time is limited, implement in this order for maximum demo impact per unit effort:

1. **14.5 Public QR Verification** — highest narrative value, low effort
2. **14.2 Tamper-Evident Report Chain** — low effort, strong trust signal
3. **14.8 Click-to-Source Citations** — low-medium effort, upgrades every existing RAG demo moment
4. **14.6 Manufacturer Scorecard** — low effort, broadens stakeholder story
5. **14.3 What-If Impact Simulator** — higher effort but the single strongest proof of "version-aware compliance," design the Rules Engine to support this from day one even if the feature itself ships later
6. **14.4 Amendment Diff Summarizer** — reuses RAG infra, good Phase 2 add-on
7. **14.1 Predictive Drift Detection** — needs data volume, good Phase 2/3
8. **14.9 Hindi Language Support** — good accessibility story, add once core flow is stable
9. **14.7 Vision-Assisted Evidence Capture** — most infra-dependent, treat as stretch goal

---

## 16. What NOT to Do

- ❌ `React → LLM → "PASS"`
- ❌ Letting the LLM calculate permissible error
- ❌ Dumping the entire PDF into a vector DB and calling it "intelligent compliance"
- ❌ Building only a chatbot
- ❌ Generating reports without traceable calculations
- ❌ Hard-coding formulas into frontend JavaScript
- ❌ Letting any of the uniqueness features (drift prediction, vision checks) auto-decide PASS/FAIL — all are advisory, human-reviewed

---

## 17. Target MVP (Vertical Slice)

```
Login → Create Manufacturer → Create Instrument → Select Model/Class →
Create Test → Enter Observations → Validate Input → Run Rules Engine →
PASS/FAIL → Show Regulatory Evidence (with click-to-source citations) →
AI Explanation → Generate PDF → Store Report → QR Verification (public-facing)
```
Bake in **14.2 (tamper-evident hashing)**, **14.5 (public QR)**, and **14.8 (click-to-source)** from the start — they're low effort and belong in the core flow, not bolted on later.

---

## 18. Target Demo Script (~3–4 minutes)

| Time | Action |
|---|---|
| 0:00–0:20 | Show dashboard incl. Manufacturer Scorecard |
| 0:20–0:45 | Register ABC-1000, 1000 kg, Class III |
| 0:45–1:20 | Enter test observations |
| 1:20–1:40 | Show automatic calculation (Observed/Permissible Error, PASS/FAIL) |
| 1:40–2:00 | Ask AI "Why did this test fail?" → click-to-source citation opens PDF |
| 2:00–2:15 | Ask "Have similar instruments failed this test?" → historical reports |
| 2:15–2:35 | Admin uploads a new amendment → run "What-If Impact Simulator" → show affected past reports |
| 2:35–2:50 | Generate standardized report, show hash-chain "Verify Integrity" check |
| 2:50–3:10 | Scan QR **on a phone, publicly, no login** → live verification page |

---

## 19. Research Tasks (assign per team member)

1. **OIML R 76** — structure, test types, accuracy classes, permissible error methodology; which parts are deterministically implementable.
2. **RAG** — chunking, metadata, hybrid retrieval, reranking, citation generation, version-aware retrieval, embedding models.
3. **Regulatory Knowledge Graph** — `Act → Rule → Amendment → Section → Requirement → Test` relationships.
4. **Rules Engine** — rule representation, decision tables, JSON rules, Drools vs custom, versioning, auditability.
5. **Report Generation** — DOCX/PDF templates, tables, images, signatures, QR, versioning.
6. **Historical Report Intelligence** — indexing with structured metadata + semantic content; drift modeling approach for 14.1.
7. **Security / Audit** — RBAC, audit logs, hash-chaining approach for 14.2, tamper detection, QR verification.
8. **Public Verification & Consumer UX** — scoped public API design, rate-limiting, mobile-friendly verification page (14.5).
9. **Multilingual & Vision** — multilingual LLM prompting approach (14.9), OCR/vision API options for nameplate matching (14.7).

---

## 20. Final Product Vision

```
                LEGAL METROLOGY INTELLIGENT PLATFORM
       ┌───────────────────┼───────────────────┐
       ▼                   ▼                   ▼
  TESTING ENGINE      REGULATORY AI       REPORT SYSTEM
       │              ┌────┴────┐              │
       │              ↓         ↓              │
       │          Regulatory  Historical        │
       │             RAG         RAG            │
       └──────────────┼─────────┼──────────────┘
                      ↓
              COMPLIANCE ENGINE
                      │
                PASS / FAIL
              ┌───────┴────────┐
              ↓                ↓
          PDF/DOCX        QR Verification
              │                │
              ↓                ↓
      Tamper-Evident      Public Verification
        Repository             Page
```

---

## 21. Provided Dataset (Regulatory Foundation)

- Legal Metrology Act, 2009
- Legal Metrology (Approval of Models) Rules, 2011 + 2019 amendment
- Legal Metrology (General) Rules, 2011 + amendments through 2025/2026
- Government Approved Test Centre Rules, 2013 + amendments
- Notifications and guidelines

This dataset is the foundation for the Regulatory RAG and demonstrates why **version-awareness and effective dates** are a first-class design requirement — directly enabling the What-If Simulator (14.3) and Amendment Diff Summarizer (14.4).
