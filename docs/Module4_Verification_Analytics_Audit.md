# Module 4 — Verification, Analytics & Audit
## Legal Metrology Testing & Compliance Platform

---

## 1. Purpose of This Module

Module 4 takes a **locked report** from Module 3 and builds the trust, oversight, and analytics layer around it: proof that the report hasn't been tampered with, a way for anyone — internal or public — to verify its authenticity, visibility into testing activity trends, and an immutable record of who did what and when across the entire platform.

This module doesn't change any compliance data. It **observes, verifies, and reports on** what Modules 1–3 already produced. Nothing here writes to `compliance_results`, `observations`, or the report content itself.

---

## 2. Precondition (dependency on Module 3)

The Hash-Chain and QR sub-components activate at the exact moment a report transitions to `LOCKED` in Module 3 (`POST /api/reports/{id}/lock`). Dashboard and Audit Trail, however, are **passive listeners** active from Module 1 onward — they don't wait for a locked report; they log and aggregate everything happening across the whole platform continuously.

---

## 3. Scope — What's Inside Module 4

```
(from Module 3: report LOCKED event)
        ↓
1. Tamper-Evident Hash-Chain Service
   (computes report_hash, links to previous_hash)
        ↓
2. QR Generation & Verification
   ├── Internal QR (certificate_id) → Report Repository
   └── Public Verification API (no-auth, read-only, rate-limited)
        ↓
   Public Verification Page (scan → status page)

(continuous, from every module)
        ↓
3. Audit Trail Service
   (logs every create/modify/approve/lock event, platform-wide)
        ↓
4. Dashboard & Manufacturer/Model Scorecard
   (aggregates from Report Repository + compliance_results)
```

---

## 4. Sub-Component 1: Tamper-Evident Hash-Chain Service

### 4.1 What it does
Cryptographically proves that a locked report's content hasn't been altered after the fact — without needing blockchain infrastructure. This is what turns "immutable audit trail" from a claim into something provable and demoable.

### 4.2 How the Chain Works
```
Report v1 → report_hash_1 = SHA-256(content_1)
Report v2 → report_hash_2 = SHA-256(content_2 + report_hash_1)
Report v3 → report_hash_3 = SHA-256(content_3 + report_hash_2)
```
Each report's hash depends on the previous one — altering any past report's content changes its hash, which breaks every hash computed after it, making tampering detectable by recomputation.

### 4.3 Implementation
- Triggered automatically inside Module 3's `POST /api/reports/{id}/lock` call — this service is called synchronously as part of locking, not as an async afterthought, so a report is never considered "locked" without a hash already attached.
- `report_hash` and `previous_hash` are stored on the `report_versions` row (schema already defined in Module 3).
- `previous_hash` is looked up as the most recent `report_hash` in the chain **for that specific instrument** (or globally, depending on how strict you want the chain scope — per-instrument chaining is usually sufficient and easier to reason about for a hackathon demo).
- A `/verify-chain/{reportId}` endpoint recomputes the entire chain from the first version forward and compares each stored hash to the recomputed one — a mismatch anywhere means the chain is broken and something was altered.

### 4.4 Implementation Detail (pseudocode)
```
function lockReport(report):
    previous = getLatestHashForInstrument(report.instrument_id)
    content_hash_input = report.content + (previous ?? "GENESIS")
    report.report_hash = SHA256(content_hash_input)
    report.previous_hash = previous
    save(report)

function verifyChain(instrument_id):
    versions = getAllReportVersionsOrdered(instrument_id)
    running_hash = "GENESIS"
    for v in versions:
        expected = SHA256(v.content + running_hash)
        if expected != v.report_hash:
            return BROKEN at v
        running_hash = v.report_hash
    return VALID
```

### 4.5 Data Model
```
report_versions (already defined in Module 3, used here)
 ├── report_hash
 ├── previous_hash
 └── created_at
```
No new tables required — this service operates on data Module 3 already owns, adding the hash computation step.

### 4.6 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/reports/{id}/verify-chain` | GET | Recompute and verify the hash chain for this report's instrument |

**UI touch:** a "Verify Integrity" button on the Report Detail screen that calls this endpoint live and shows a green (valid) or red (broken) result — a strong, tangible demo moment.

---

## 5. Sub-Component 2: QR Generation & Public Verification

### 5.1 What it does
Issues a scannable QR per locked report, and — critically — separates **internal** report access from a **public-facing**, no-login verification path. This reframes the platform from an internal lab tool into consumer-protection infrastructure.

### 5.2 Two Distinct Access Paths

| Path | Who uses it | What it exposes |
|---|---|---|
| Internal Report Repository API (Module 3) | Lab staff, logged in | Full report: observations, calculations, evidence, reviewer info |
| **Public Verification API** (this module) | Anyone scanning the QR, no login | Minimal fields only: instrument model, verification status, verification date, validity period |

This separation is a deliberate security boundary: the public endpoint must be architecturally incapable of returning internal report content, no matter what parameter is passed to it.

### 5.3 Implementation

**QR Generation (internal, triggered on lock):**
- Generate a `certificate_id` — a new, random identifier distinct from the internal `report_id`, so scanning a QR never reveals or allows guessing internal database keys.
- Encode a URL like `https://<platform-domain>/verify/{certificate_id}` into a QR image, stored alongside the report.

**Public Verification API:**
- `GET /api/public/verify/{certificate_id}` — **no authentication required**.
- Rate-limited (e.g., per-IP throttling) to prevent scraping/abuse.
- Returns only: `{ instrument_model, manufacturer_name, verification_status, verification_date, validity_until }`.
- Never returns: `report_id`, observations, calculations, reviewer identity, lab internal notes, or any Module 1–3 internal identifiers.

**Public Verification Page:**
- A simple, mobile-friendly web page (no app install) that calls the above endpoint and renders "VALID — Verified [date], Class III" or "INVALID / EXPIRED / NOT FOUND."
- Opened directly by scanning the QR with any phone camera — this is a strong, physical closing beat for a live demo.

### 5.4 Data Model
```
certificates
 ├── id
 ├── certificate_id        (public-facing, random, not the internal report_id)
 ├── report_id             (internal FK — never exposed via public API)
 ├── instrument_id
 ├── qr_image_path
 ├── validity_from
 ├── validity_until
 └── issued_at
```

### 5.5 API Endpoints

| Endpoint | Method | Purpose | Auth |
|---|---|---|---|
| `/api/reports/{id}/generate-qr` | POST | Generate certificate + QR on lock | Internal, authenticated |
| `/api/certificates/{certificate_id}` | GET | Internal lookup (full detail, for lab staff) | Internal, authenticated |
| `/api/public/verify/{certificate_id}` | GET | Public verification (minimal fields) | **None — public** |

---

## 6. Sub-Component 3: Audit Trail Service

### 6.1 What it does
Provides an immutable, platform-wide log of every meaningful action, from Module 1 through Module 4 — independent of, and complementary to, the Hash-Chain (the hash chain proves report *content* wasn't altered; the audit trail proves *who did what and when* across the whole system).

### 6.2 Events Logged (examples across all modules)

| Event | Module of origin |
|---|---|
| Instrument registered | Module 1 |
| Applicability decision made | Module 1 |
| Evidence uploaded | Module 1 |
| Observation entered / modified | Module 2 |
| Rules Engine evaluation run | Module 2 |
| Regulatory assessment generated | Module 3 |
| Reviewer approved / returned | Module 3 |
| Report generated / locked | Module 3 |
| Certificate/QR issued | Module 4 |

### 6.3 Implementation
- A cross-cutting concern: every service in every module emits an event to this service on any state-changing action — implemented cleanly via a Spring event listener pattern (`ApplicationEventPublisher`) rather than scattering manual logging calls everywhere, so adding new audited actions later doesn't require touching this service's core code.
- Each log entry is **append-only** — no update or delete endpoint exists for audit records, by design.

### 6.4 Data Model
```
audit_logs
 ├── id
 ├── user_id
 ├── action                 (e.g. "OBSERVATION_MODIFIED", "REPORT_LOCKED")
 ├── entity_type             (e.g. "test_case", "report")
 ├── entity_id
 ├── old_value (JSON, nullable)
 ├── new_value (JSON, nullable)
 └── timestamp
```

### 6.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/audit-logs` | GET | Search logs (filter by entity, user, action type, date range) |
| `/api/audit-logs/entity/{type}/{id}` | GET | Full audit history for one specific record (e.g., one report's full lifecycle) |

---

## 7. Sub-Component 4: Dashboard & Manufacturer/Model Scorecard

### 7.1 What it does
Gives labs (and, for the Scorecard specifically, regulators) visibility into testing activity and compliance trends across the whole platform — read-only aggregation over data owned by Modules 2 and 3.

### 7.2 Core Dashboard Metrics

| Metric | Source |
|---|---|
| Total Instruments Tested | Count of distinct `instruments` with a `test_session` |
| Tests In Progress / Completed | `test_sessions.status` |
| Passed / Failed | `compliance_results.final_result` |
| Reports Generated | Count of `reports` |
| Tests over time | Time-bucketed count of `test_sessions` |
| PASS/FAIL distribution | Aggregate of `compliance_results` |
| Instruments by manufacturer | Join `instruments` → `manufacturers` |
| Pending reviews | Count of `regulatory_assessments.status = PENDING_REVIEW` |

### 7.3 Manufacturer/Model Quality Scorecard (differentiator feature)
- Aggregates `compliance_results` by `manufacturer_id` and `model_name` to compute pass rate, fail rate, and the most common failing test type per manufacturer/model.
- Surfaces manufacturers/models whose failure rate exceeds a configurable threshold.
- **Role-restricted** to Administrator/Reviewer — this is a regulatory-oversight view, not something a Test Engineer needs day-to-day.

### 7.4 Implementation
- Pure SQL aggregation queries (or materialized views refreshed on a schedule for performance) against existing tables — no new source-of-truth data needed.
- No new write paths; this sub-component is entirely read-only.

### 7.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/dashboard/summary` | GET | Core metrics (totals, PASS/FAIL counts, in-progress counts) |
| `/api/dashboard/trends` | GET | Time-series data for charts |
| `/api/dashboard/scorecard` | GET | Manufacturer/model pass-rate aggregation (Admin/Reviewer only) |

---

## 8. End-to-End Module 4 Flow (Summary)

1. Module 3 locks a report → Hash-Chain Service computes and stores `report_hash`/`previous_hash` synchronously.
2. QR Service issues a `certificate_id` and QR image, distinct from the internal report ID.
3. Anyone scanning the QR hits the isolated Public Verification API and sees a minimal, read-only status page — no path back into internal data.
4. Meanwhile, every action across Modules 1–3 has been continuously logged to the Audit Trail.
5. Dashboard and Scorecard continuously aggregate from the Report Repository and `compliance_results` in the background, giving labs and regulators an always-current view.

---

## 9. Module 4 — Complete API Surface

| Endpoint | Method | Purpose | Auth |
|---|---|---|---|
| `/api/reports/{id}/verify-chain` | GET | Verify hash-chain integrity | Internal |
| `/api/reports/{id}/generate-qr` | POST | Issue certificate + QR | Internal |
| `/api/certificates/{certificate_id}` | GET | Full internal certificate lookup | Internal |
| `/api/public/verify/{certificate_id}` | GET | Public status check | **None** |
| `/api/audit-logs` | GET | Search audit logs | Internal (Admin/Reviewer) |
| `/api/audit-logs/entity/{type}/{id}` | GET | Full history for one record | Internal |
| `/api/dashboard/summary` | GET | Core metrics | Internal |
| `/api/dashboard/trends` | GET | Time-series charts | Internal |
| `/api/dashboard/scorecard` | GET | Manufacturer/model scorecard | Internal (Admin/Reviewer) |

---

## 10. Tech Stack for This Module

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot |
| Hashing | Standard `java.security.MessageDigest` (SHA-256) — no external crypto library needed |
| QR generation | ZXing (Java QR code library) |
| Database | PostgreSQL (same instance; consider a materialized view for Dashboard aggregations if data volume grows) |
| Rate limiting (public API) | Spring's built-in filters or a lightweight bucket4j-style limiter |
| Frontend | React — Dashboard charts (recharts-style), Scorecard table, a minimal standalone Public Verification page served separately from the main authenticated app |

---

## 11. MVP Build Order Within Module 4

1. **Audit Trail first** — it's the simplest, and wiring the event-listener pattern early means every module built from here on (and retrofitted from Modules 1–3) gets logging for free.
2. **Hash-Chain Service** — low effort, high trust payoff; wire it into Module 3's `lock` endpoint.
3. **QR + Public Verification** — build the internal certificate issuance first, then the scoped public endpoint, then the simple public page last (it's the most visible demo piece but depends on the other two existing first).
4. **Dashboard core metrics** — basic counts and PASS/FAIL distribution; charts can be simple bar/line visualizations.
5. **Scorecard** — build last within this module; it depends on having enough `compliance_results` data across multiple manufacturers to be meaningful in a demo (seed some varied test data if real volume is low).

**Definition of done for Module 4:** a locked report has a verifiable hash chain (with a working "Verify Integrity" check), a QR that opens a live, public, no-login status page showing only minimal safe fields, a complete and queryable audit history from registration through locking, and a dashboard showing real aggregated numbers from the reports generated so far.

---

## 12. What NOT to Do in This Module

- ❌ Do not let the Public Verification API return anything beyond the minimal fields listed in Section 5.3 — no observations, no internal report ID, no reviewer identity.
- ❌ Do not expose the internal `report_id` anywhere reachable from the public QR flow — only the separately-generated `certificate_id`.
- ❌ Do not make the Hash-Chain Service asynchronous/best-effort — a report should never be considered `LOCKED` without its hash already computed and stored.
- ❌ Do not allow any update or delete endpoint on `audit_logs` — it must stay strictly append-only.
- ❌ Do not let the Scorecard or Dashboard write back into `compliance_results` or any source table — these are read-only aggregation views, full stop.
- ❌ Do not skip rate-limiting on the public verification endpoint — an open, unauthenticated API without throttling is an easy target for scraping or abuse.
