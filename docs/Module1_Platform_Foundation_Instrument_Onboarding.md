# Module 1 — Platform Foundation & Instrument Onboarding
## Legal Metrology Testing & Compliance Platform

---

## 1. Purpose of This Module

Module 1 is the **entry point of the entire platform**. Nothing else can be built or demoed until this module works, because every later module depends on:
- A logged-in, role-checked user
- A registered instrument with correct technical parameters
- A confirmed **Legal Metrology applicability decision** for that instrument
- Verified supporting evidence (documents/photos)
- Data that has passed validation before it's trusted anywhere downstream

**This module ends the moment an instrument is confirmed as "applicable" and ready for test session creation — it does not include any OIML testing or calculation logic (that's Module 2).**

---

## 2. Scope — What's Inside Module 1

```
User Login / Auth
        ↓
RBAC & User Roles
        ↓
1. Equipment Registration
        ↓
2. Legal Metrology Applicability Check ──NOT APPLICABLE──> Refer / Stop
        │ APPLICABLE
        ▼
3. Requirement & Test Determination (produces required_tests list)
        ↓
4. Document & Evidence Collection
        ↓
   ┌────┴────┐
   ▼         ▼
 OCR/Vision   Document Extraction
   └────┬────┘
        ▼
5. Data Validation (instrument + document level)
        ↓
   Instrument is READY → handed off to Module 2 (Test Session Creation)
```

---

## 3. Sub-Component 1: Authentication & RBAC

### 3.1 What it does
Secures every action in the platform behind a login and a role check.

### 3.2 Roles

| Role | Permissions within Module 1 |
|---|---|
| **Administrator** | Manage users, manage applicability rule sets, view all instruments |
| **Test Engineer** | Register instruments, run applicability checks, upload evidence |
| **Reviewer** | View registered instruments and their applicability decisions (read-only here; full powers begin in Module 3) |
| **Viewer** | Read-only search access to instrument records |

### 3.3 Implementation
- **Tech:** Spring Security + JWT.
- On successful login, issue a JWT containing `user_id`, `role`, and `laboratory_id` (a user typically belongs to one testing lab).
- Every controller method in Module 1 is annotated with `@PreAuthorize("hasRole('TEST_ENGINEER')")` or equivalent — enforced at the method level, not just at the UI.
- Passwords hashed with bcrypt; no plaintext storage.

### 3.4 Data Model
```
users
 ├── id
 ├── name
 ├── email
 ├── password_hash
 ├── role              (ADMIN / TEST_ENGINEER / REVIEWER / VIEWER)
 ├── laboratory_id
 └── created_at

laboratories
 ├── id
 ├── name
 ├── address
 └── accreditation_number
```

### 3.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/auth/login` | POST | Authenticate, return JWT |
| `/api/auth/me` | GET | Return current user + role |
| `/api/users` | POST | Admin creates a new user |
| `/api/users/{id}/role` | PATCH | Admin changes a user's role |

---

## 4. Sub-Component 2: Equipment Registration

### 4.1 What it does
Captures the technical identity of the instrument being brought in for testing. This is **pure data capture** — no compliance logic runs yet.

### 4.2 Fields Captured

| Field | Example | Notes |
|---|---|---|
| Manufacturer | ABC Weighing Systems Pvt. Ltd. | Links to `manufacturers` table |
| Model | ABC-1000 | |
| Serial Number | ABC10002345 | Must be unique per manufacturer+model |
| Instrument Type | Platform scale / Weighbridge / Electronic scale | |
| Intended Use | Trade, healthcare, industrial, internal-only | **This field is critical input to the Applicability Check next** |
| Max Capacity | 1000 kg | |
| Min Capacity | 20 kg | |
| Scale Interval (e) | 0.5 kg | |
| Accuracy Class | I / II / III / IIII | Determines which OIML R76 rule table applies later |
| Technical Specifications | free-text/structured extras | |

### 4.3 Implementation
- `POST /api/instruments` writes to the `instruments` table.
- `manufacturers` is a separate lookup table — auto-create-if-not-exists when a new manufacturer name is entered, to avoid free-text duplication.
- No validation beyond basic required-field checks happens here — the **real** validation gate is Sub-Component 5, which runs after evidence collection.

### 4.4 Data Model
```
manufacturers
 ├── id
 ├── name
 └── country

instruments
 ├── id
 ├── manufacturer_id
 ├── model_name
 ├── serial_number
 ├── instrument_type
 ├── intended_use
 ├── max_capacity
 ├── min_capacity
 ├── scale_interval (e)
 ├── accuracy_class
 ├── technical_specs (JSON)
 ├── applicability_status   (PENDING / APPLICABLE / NOT_APPLICABLE)
 ├── registered_by (user_id)
 └── created_at
```

### 4.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/manufacturers` | POST/GET | Create/list manufacturers |
| `/api/instruments` | POST | Register a new instrument |
| `/api/instruments/{id}` | GET | Fetch instrument details |
| `/api/instruments` | GET | List/search instruments (filterable by manufacturer, status) |

---

## 5. Sub-Component 3: Legal Metrology Applicability Engine — *the core differentiator of this module*

### 5.1 What it does
Determines, **deterministically**, whether this instrument falls under Legal Metrology scope, which Indian rules apply, and whether it needs to proceed to OIML R76 testing at all.

### 5.2 Why It's Deterministic, Not an LLM Call
Just like the compliance Rules Engine in Module 2, this decision has legal weight — it decides whether a lab even proceeds with testing. It must be:
- Reproducible
- Traceable to a specific rule/clause
- Never subject to LLM variance

The Regulatory RAG **may** be consulted to *surface a supporting citation* to the engineer, but the actual applicability verdict comes from a rule table.

### 5.3 Decision Logic (structure)

```
INPUT: instrument_type, intended_use, accuracy_class declared

Rule table (JSON-configured, versioned — same pattern as OIML rules):
 - IF intended_use IN ["trade", "commerce", "healthcare_billing"]
      → APPLICABLE, cite: Legal Metrology Act 2009, Section X
 - IF intended_use == "internal_only" AND instrument_type == "lab_reference_scale"
      → NOT_APPLICABLE, cite: General Rules 2011, exemption clause Y
 - IF model already has a valid, unexpired model approval on file
      → APPLICABLE (skip model approval requirement) + flag "renewal/verification only"
 - ELSE → APPLICABLE (default: assume in-scope, require full path)
```

### 5.4 Implementation
- A dedicated `ApplicabilityRuleSet` stored as versioned JSON (mirrors the `standard_versions` pattern used later for OIML rules) — so a change in interpretation or regulation doesn't require a code redeploy.
- `POST /api/instruments/{id}/applicability-check` runs the engine and writes the result to `instruments.applicability_status` plus a new `applicability_decisions` audit row.
- If **NOT_APPLICABLE**: instrument is flagged, no further testing module actions are permitted on it, and it's visible in the repository as "Not Applicable — [reason + cited rule]."
- If **APPLICABLE**: proceeds automatically to Requirement & Test Determination (5.5).

### 5.5 Requirement & Test Determination (runs immediately after a positive applicability result)

**What it does:** Given the instrument's accuracy class and type, determines the specific set of OIML R76 tests required (e.g., Weighing performance, Repeatability, Eccentricity, Zero/tare, Influence factors).

**Implementation:** A second decision table keyed on `accuracy_class` + `instrument_type` → `required_tests[]`. This list is stored against the instrument and handed to Module 2 to render the correct test forms.

### 5.6 Data Model
```
applicability_rule_sets
 ├── id
 ├── version_label
 ├── effective_from
 └── rules (JSON)

applicability_decisions
 ├── id
 ├── instrument_id
 ├── result              (APPLICABLE / NOT_APPLICABLE)
 ├── matched_rule_id
 ├── cited_clause
 ├── required_tests (JSON array)  -- populated only if APPLICABLE
 ├── decided_at
 └── decided_by (user_id, or SYSTEM)
```

### 5.7 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/instruments/{id}/applicability-check` | POST | Run the applicability engine |
| `/api/instruments/{id}/applicability` | GET | Fetch the decision + citation |
| `/api/applicability-rules` | GET/POST | Admin manages rule versions |

---

## 6. Sub-Component 4: Document & Evidence Collection

### 6.1 What it does
Collects supporting evidence needed both for applicability confirmation and for the eventual model-approval assessment (Module 3): nameplate photo, manual, datasheet, model approval document, certificates, drawings, instrument photographs.

### 6.2 Implementation
- File upload endpoint → stores binary in **Object Storage**, metadata row in `attachments` table linked to the instrument.
- Two parallel extraction pipelines run automatically on upload:

**a) OCR / Computer Vision** (on photos)
- Extracts: Model, Serial No., Max/Min, `e`/`d` from the nameplate image.
- Compares extracted values against what was manually entered in Sub-Component 2.
- Mismatches are **flagged, never auto-corrected** — surfaced as a warning on the instrument record for a human to resolve.

**b) Document Extraction** (on manuals/datasheets/certificates)
- Text extraction (PDF parsing, OCR for scanned certificates) to confirm declared technical specs match the manufacturer's own documentation.

### 6.3 Data Model
```
attachments
 ├── id
 ├── instrument_id
 ├── file_type          (nameplate_photo / manual / datasheet / certificate / drawing / other)
 ├── storage_path
 ├── uploaded_by
 └── uploaded_at

extraction_results
 ├── id
 ├── attachment_id
 ├── extraction_type     (ocr / document_text)
 ├── extracted_fields (JSON)
 ├── mismatch_flag (boolean)
 ├── mismatch_details (text)
 └── extracted_at
```

### 6.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/instruments/{id}/attachments` | POST | Upload a document/photo |
| `/api/instruments/{id}/attachments` | GET | List uploaded evidence |
| `/api/attachments/{id}/extraction` | GET | View OCR/extraction result + any mismatch flags |

---

## 7. Sub-Component 5: Data Validation (Instrument-Level Gate)

### 7.1 What it does
The final hard gate before an instrument is considered "ready" for Module 2. This is deliberately **separate** from the observation-level validation that happens later in Module 2 — this gate checks instrument and document data, not test data.

### 7.2 Checks Performed
- All required registration fields present (Sub-Component 2)
- Values within plausible physical ranges (e.g., Max > Min > 0)
- Unit consistency (no mixing kg/g/lb without conversion)
- Model/serial consistency: does OCR-extracted serial number match the registered serial number?
- Configuration checks: does the declared accuracy class match what the datasheet states?
- No unresolved mismatch flags from Sub-Component 4 extraction

### 7.3 Implementation
- A dedicated `ValidationService` class, called by `POST /api/instruments/{id}/validate`.
- Returns either `READY` or a list of specific validation failures with field-level detail (not just "invalid data").
- Only an instrument in `READY` status can have a test session created against it in Module 2 — enforced by a status check in Module 2's API, not just the UI.

### 7.4 Data Model
```
instruments (extended)
 └── validation_status   (PENDING / READY / FAILED)

validation_results
 ├── id
 ├── instrument_id
 ├── check_name
 ├── passed (boolean)
 ├── detail
 └── checked_at
```

### 7.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/instruments/{id}/validate` | POST | Run the validation gate |
| `/api/instruments/{id}/validation-results` | GET | View detailed pass/fail per check |

---

## 8. End-to-End Module 1 Flow (Summary)

1. Test Engineer logs in → JWT issued with role.
2. Engineer registers manufacturer + instrument (Sub-Component 2).
3. System immediately runs the **Legal Metrology Applicability Check** (Sub-Component 3).
   - If NOT_APPLICABLE → flow stops, logged, visible to Reviewer/Admin as closed-out.
   - If APPLICABLE → `required_tests` list generated.
4. Engineer uploads evidence: nameplate photo, datasheet, certificates (Sub-Component 4).
5. OCR/Vision and Document Extraction run automatically, flagging any mismatches.
6. Engineer runs final Data Validation (Sub-Component 5).
   - If FAILED → specific errors returned, engineer corrects and re-validates.
   - If READY → instrument is now eligible for Module 2 (Test Session Creation).

---

## 9. Module 1 — Complete API Surface

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/auth/login` | POST | Authenticate |
| `/api/auth/me` | GET | Current user info |
| `/api/users` | POST | Create user (admin) |
| `/api/users/{id}/role` | PATCH | Change role (admin) |
| `/api/manufacturers` | POST/GET | Manage manufacturers |
| `/api/instruments` | POST/GET | Register/list instruments |
| `/api/instruments/{id}` | GET | Instrument detail |
| `/api/instruments/{id}/applicability-check` | POST | Run applicability engine |
| `/api/instruments/{id}/applicability` | GET | View applicability decision |
| `/api/applicability-rules` | GET/POST | Manage applicability rule versions (admin) |
| `/api/instruments/{id}/attachments` | POST/GET | Upload/list evidence |
| `/api/attachments/{id}/extraction` | GET | View OCR/extraction result |
| `/api/instruments/{id}/validate` | POST | Run final validation gate |
| `/api/instruments/{id}/validation-results` | GET | View validation detail |

---

## 10. Tech Stack for This Module

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot, Spring Security |
| Database | PostgreSQL |
| File storage | Object storage (local disk for MVP, S3-compatible for production) |
| OCR/Vision | Any vision-capable API/model for nameplate text extraction (can be stubbed with a mocked response for early MVP demo) |
| Frontend | React + Vite + Tailwind + shadcn/ui — registration form, applicability result screen, evidence upload UI, validation status panel |

---

## 11. MVP Build Order Within Module 1

1. Auth + RBAC (login, JWT, role annotations) — no business value yet, but everything else depends on it.
2. Manufacturer + Instrument registration (basic CRUD).
3. Applicability Engine — **start with a single hardcoded rule set** (e.g., "trade use → always applicable") to prove the branch works end-to-end, then expand the rule table.
4. Evidence upload (file storage + metadata) — OCR/Vision integration can be stubbed/mocked initially if a real vision API isn't ready; wire the real one in once the flow works.
5. Validation gate — start with the 2–3 highest-value checks (required fields, serial match) before adding the full check list.

**Definition of done for Module 1:** an instrument can be registered, correctly routed through APPLICABLE or NOT_APPLICABLE, have evidence attached, and reach `READY` status — fully role-gated and with every decision (applicability + validation) stored and viewable, ready to hand off to Module 2.

---

## 12. What NOT to Do in This Module

- ❌ Do not let the Applicability Engine call the LLM to decide APPLICABLE/NOT_APPLICABLE — RAG is advisory citation only.
- ❌ Do not skip storing the `matched_rule_id` and `cited_clause` on the applicability decision — this traceability is what makes the decision defensible later.
- ❌ Do not auto-correct OCR mismatches silently — always surface them for human resolution.
- ❌ Do not allow Module 2 (test session creation) to proceed for an instrument that isn't `applicability_status = APPLICABLE` and `validation_status = READY` — enforce this as a backend guard, not just a UI restriction.
