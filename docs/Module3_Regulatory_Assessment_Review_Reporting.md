# Module 3 — Regulatory Assessment, Review & Reporting
## Legal Metrology Testing & Compliance Platform

---

## 1. Purpose of This Module

Module 3 takes the **completed technical result** from Module 2 (a stored, versioned PASS/FAIL) and turns it into something a real Legal Metrology lab can actually issue: a **reviewed, approved, standardized report**.

This module is where the platform stops being "just a calculator" and becomes a compliance product — a technical PASS does not automatically mean a report gets issued. It must be combined with regulatory context, checked for documentation completeness, and signed off by a qualified human.

**Module 3 ends when a report is generated and locked** — QR issuance, the public verification page, dashboards, and audit trail live in Module 4; AI-assisted explanation lives in Module 5 (though Module 3's Reviewer screen *calls into* Module 5 for explanations, it doesn't own that logic).

---

## 2. Precondition (hard dependency on Module 2)

Module 3 must refuse to start regulatory assessment unless:
```
test_session.status == COMPLETED
compliance_results exist for every required_test in the session
```
Enforced as a backend guard on `POST /api/test-sessions/{id}/regulatory-assessment`, not just a UI restriction.

---

## 3. Scope — What's Inside Module 3

```
(from Module 2: COMPLETED test session, Technical Results package)
        ↓
1. Regulatory / Model Approval Assessment
   (Technical Results + Applicability Decision [Module 1] +
    Evidence Checklist [Module 1] + Model Approval Requirements)
        ↓
2. Reviewer Assessment
   ├── Review observations, calculations, evidence, regulatory data
   ├── (optionally) consult AI explanation — calls into Module 5
   └── Approve / Return
        ↓
   ┌────┴────┐
RETURNED    APPROVED
   │            │
   ▼            ▼
back to      3. Report Generation (PDF + DOCX)
Module 2            ↓
              4. Report Repository (storage, versions, search)
                     ↓
              Handed off to Module 4 (QR, Dashboard, Audit Trail)
```

---

## 4. Sub-Component 1: Regulatory / Model Approval Assessment

### 4.1 What it does
Combines four separate inputs into a single assessment package for the Reviewer — this is an **aggregation step**, not a second automated decision layer. It never overrides or re-decides anything Module 1 or Module 2 already determined.

### 4.2 Inputs Combined

| Input | Source |
|---|---|
| Technical test results (PASS/FAIL per test, calculated values) | Module 2 |
| Applicable regulatory requirements + cited clause | Module 1's Applicability Engine |
| Required documents/evidence + any unresolved mismatch flags | Module 1's Document & Evidence Collection |
| Model approval requirements (is this a new model, or a renewal against an existing approval?) | Manufacturer/model record |

### 4.3 Implementation
- `GET /api/test-sessions/{id}/regulatory-assessment` is a **read/compose** endpoint — it doesn't write new decisions, it assembles existing data from three prior modules into one view.
- Checks documentation completeness against a configurable checklist (e.g., "model approval doc required for new models," "certificate of calibration required for reference standards used") and flags anything missing as `incomplete`.
- If the checklist is incomplete, the assessment is still generated but marked `INCOMPLETE_DOCUMENTATION` — the Reviewer sees this clearly and can choose to return the session for more evidence rather than approve.

### 4.4 Data Model
```
regulatory_assessments
 ├── id
 ├── test_session_id
 ├── technical_result_summary (JSON, denormalized snapshot)
 ├── applicability_decision_id (FK to Module 1)
 ├── documentation_status      (COMPLETE / INCOMPLETE)
 ├── missing_documents (JSON array, if any)
 ├── model_approval_type        (NEW / RENEWAL / NOT_REQUIRED)
 ├── generated_at
 └── status                     (PENDING_REVIEW / APPROVED / RETURNED)
```

### 4.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-sessions/{id}/regulatory-assessment` | GET | Generate/fetch the assembled assessment view |

---

## 5. Sub-Component 2: Reviewer Assessment Workflow

### 5.1 What it does
The **human-in-the-loop checkpoint** the entire platform is built around. Everything upstream — applicability decision, deterministic calculations, documentation checks, and any AI explanation — feeds into this one decision point, and nothing becomes a final report without it.

### 5.2 What the Reviewer Sees and Does

| Action | Detail |
|---|---|
| Review observations | Raw data from Module 2, per test case |
| Review calculations | Observed error, permissible error, rule version used |
| Review evidence | Uploaded documents/photos from Module 1, including any unresolved OCR mismatch flags |
| Review regulatory data | The assembled Regulatory Assessment from Sub-Component 1 |
| Review AI explanation | On-demand — Reviewer can ask "why did this fail?" which calls into Module 5's Regulatory RAG + LLM; this is read-only context, never a decision input the system enforces |
| Approve | Session moves to Report Generation |
| Return | Session goes back to Module 2 (if the issue is testing/observation-related) or Module 1 (if it's an evidence/documentation gap), with reviewer comments attached |

### 5.3 Implementation
- `PATCH /api/regulatory-assessments/{id}/review` with body `{ decision: "APPROVE" | "RETURN", comments: "...", returned_to_module: "MODULE_1" | "MODULE_2" }`.
- On `RETURN`, the underlying `test_session` or `instrument` status is reset appropriately so the relevant module knows corrective action is needed — e.g., returning to Module 2 sets `test_session.status = IN_PROGRESS` again with the reviewer's comment visible to the Test Engineer.
- On `APPROVE`, this is the trigger event for Report Generation (Sub-Component 3).
- Role-gated: only users with the `REVIEWER` role can call this endpoint (enforced via `@PreAuthorize`, per Module 1's RBAC).

### 5.4 Data Model
```
regulatory_assessments (extended)
 ├── reviewer_id
 ├── review_decision       (APPROVE / RETURN)
 ├── review_comments
 ├── returned_to_module    (nullable)
 └── reviewed_at
```

### 5.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/regulatory-assessments/{id}/review` | PATCH | Reviewer approves or returns |
| `/api/regulatory-assessments/{id}/ask-ai` | POST | Optional — proxy call into Module 5's AI Assistant for an explanation, scoped to this session's data |

---

## 6. Sub-Component 3: Report Generation

### 6.1 What it does
Once approved, generates the standardized, final test report in both PDF (for issuing/printing) and editable DOCX (for internal correction/reuse) formats.

### 6.2 Report Contents

| Section | Populated from |
|---|---|
| Instrument details | Module 1 (`instruments`) |
| Laboratory & test session context | Module 2 (`test_sessions`) |
| Test observations | Module 2 (`observations`) |
| Calculations | Module 2 (`calculations`) |
| PASS/FAIL (per test + overall) | Module 2 (`compliance_results`) |
| Regulatory information (applicability clause, model approval status) | Module 1 + this module's Regulatory Assessment |
| Evidence/photos | Module 1 (`attachments`) |
| Reviewer information | This module (`reviewer_id`, decision, timestamp) |

### 6.3 Implementation
- Template-based generation: a fixed layout template with placeholders, populated server-side.
- Recommended libraries: a PDF templating library (e.g., iText or a Java PDF-generation library) for PDF; Apache POI (or similar) for DOCX.
- Generation is triggered automatically on `APPROVE` (Sub-Component 2) — `POST /api/regulatory-assessments/{id}/generate-report` can also be called explicitly if regeneration is needed (e.g., after a correction cycle).
- **Recommended addition (ties to Module 4):** at the moment a report is finalized/locked, pass its content to the Hash-Chain Service so `report_hash` and `previous_hash` are computed as part of this same generation step, not bolted on afterward.

### 6.4 Data Model
```
reports
 ├── id
 ├── test_session_id
 ├── regulatory_assessment_id
 ├── report_number           (human-readable, e.g. NAWI-2026-00125)
 ├── pdf_storage_path
 ├── docx_storage_path
 ├── status                  (DRAFT / LOCKED)
 ├── locked_at
 └── generated_at

report_versions
 ├── id
 ├── report_id
 ├── version_number
 ├── report_hash              (set by Module 4's Hash-Chain Service)
 ├── previous_hash
 └── created_at
```

### 6.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/regulatory-assessments/{id}/generate-report` | POST | Generate PDF + DOCX |
| `/api/reports/{id}` | GET | Fetch report metadata + download links |
| `/api/reports/{id}/lock` | POST | Finalize/lock the report (triggers hash-chain) |

---

## 7. Sub-Component 4: Report Repository

### 7.1 What it does
The permanent, searchable home for every generated report — a standard relational store, **not** RAG (that's a separate, parallel indexing step Module 5 performs on top of this same data).

### 7.2 Features

| Feature | Detail |
|---|---|
| Instrument-wise history | All reports for a given instrument, chronologically |
| Search | By manufacturer, model, serial number, status, date range |
| Filtering | By PASS/FAIL, by laboratory, by reviewer |
| Report versions | If a report is regenerated after correction, all versions are retained, not overwritten |

### 7.3 Implementation
- `GET /api/reports` with query parameters for search/filter — backed by standard indexed PostgreSQL columns (`manufacturer_id`, `model_name`, `status`, `created_at`).
- This is the data source Module 4's Dashboard and Module 5's Historical RAG both read from — but neither writes back into it; report data becomes read-only once locked.

### 7.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/reports` | GET | Search/list reports (filters: manufacturer, model, status, date range) |
| `/api/reports/{id}` | GET | Full report detail (drill-down: instrument, lab info, environmental conditions, observations, calculations, results, photos, attachments, generated report, audit history) |
| `/api/instruments/{id}/reports` | GET | All reports for a specific instrument |

---

## 8. End-to-End Module 3 Flow (Summary)

1. Module 2 hands off a `COMPLETED` test session with a full Technical Results package.
2. System assembles the Regulatory/Model Approval Assessment — combining technical results, applicability decision, evidence checklist, and model approval status.
3. Reviewer opens the assessment, examines observations/calculations/evidence/regulatory data, optionally asks the AI Assistant for an explanation of any FAIL.
4. Reviewer decides:
   - **Return** → sent back to Module 1 or Module 2 with comments, cycle repeats.
   - **Approve** → triggers Report Generation.
5. PDF + DOCX generated, hash-chained, and locked.
6. Report stored in the Repository, now searchable and ready for Module 4 (QR/Dashboard/Audit) and Module 5 (Historical RAG indexing).

---

## 9. Module 3 — Complete API Surface

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-sessions/{id}/regulatory-assessment` | GET | Generate/fetch assessment view |
| `/api/regulatory-assessments/{id}/review` | PATCH | Approve or return |
| `/api/regulatory-assessments/{id}/ask-ai` | POST | Optional AI explanation proxy |
| `/api/regulatory-assessments/{id}/generate-report` | POST | Generate PDF/DOCX |
| `/api/reports/{id}` | GET | Report detail |
| `/api/reports/{id}/lock` | POST | Finalize/lock report |
| `/api/reports` | GET | Search/list reports |
| `/api/instruments/{id}/reports` | GET | Reports for one instrument |

---

## 10. Tech Stack for This Module

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot |
| Database | PostgreSQL |
| PDF generation | iText or equivalent Java PDF library |
| DOCX generation | Apache POI or equivalent |
| File storage | Same Object Storage as Module 1's evidence attachments |
| Frontend | React — Reviewer dashboard screen, report preview/download UI |

---

## 11. MVP Build Order Within Module 3

1. Regulatory Assessment assembly endpoint — start by just concatenating existing data from Modules 1–2 into one JSON response; documentation-completeness checking can be a simple flag for MVP (full checklist logic later).
2. Reviewer review endpoint (`APPROVE`/`RETURN`) — wire the state transitions back to Module 1/2 correctly; this is the piece most likely to have edge cases, so test the "return" path early, not just the happy "approve" path.
3. Report Generation — start with **PDF only**, using a single fixed template populated from the worked example data (ABC-1000 scenario). Add DOCX generation once PDF output is correct.
4. Report Repository — basic list/search/detail endpoints; full-text/multi-filter search can be added incrementally.
5. Wire the hash-chain call at the `lock` step once Module 4 exists — for early MVP demos, `lock` can be a no-op status flip until then.

**Definition of done for Module 3:** an approved test session produces a downloadable PDF report reflecting the actual observations/calculations from Module 2, a Reviewer can approve or return a session with comments that correctly route it back upstream, and locked reports are searchable in the repository.

---

## 12. What NOT to Do in This Module

- ❌ Do not let the Regulatory Assessment step re-run or override the Module 2 PASS/FAIL — it only aggregates and displays it.
- ❌ Do not let a report be generated from a session that hasn't been explicitly `APPROVE`d by a Reviewer — no auto-approval path, even for a clean PASS.
- ❌ Do not overwrite a report on regeneration — always create a new `report_versions` row so the repository's history stays intact.
- ❌ Do not let the "ask AI" explanation feature influence or be recorded as part of the Reviewer's actual approve/return decision — it's context, not a vote.
- ❌ Do not skip capturing `returned_to_module` on a Return decision — without it, Module 1/2 won't know whether the fix needed is an evidence gap or a testing/data issue.
