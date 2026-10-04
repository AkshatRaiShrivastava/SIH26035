# Legal Metrology Testing & Compliance Platform
## Corrected System Architecture + Point-to-Point Implementation Guide

> This version corrects the flow from the earlier draft: **Legal Metrology applicability is checked before OIML testing even starts**, not after. This is the single most important structural change — it prevents the system from running tests on instruments that don't fall under regulatory scope, and reflects how real Legal Metrology work actually proceeds.

---

## PART A — Complete System Architecture

```
╔══════════════════════════════════════════════════════════════════════════════════════╗
║          AI-ASSISTED LEGAL METROLOGY TESTING & COMPLIANCE PLATFORM                 ║
╚══════════════════════════════════════════════════════════════════════════════════════╝


 ┌───────────────────────────────────────────────────────────────────────────────────┐
 │                         AUTHORITATIVE KNOWLEDGE SOURCES                            │
 ├───────────────────────────┬───────────────────────────┬───────────────────────────┤
 │                           │                           │                           │
 ▼                           ▼                           ▼                           ▼
┌───────────────┐     ┌────────────────┐       ┌─────────────────┐       ┌──────────────┐
│ Legal         │     │ Approval of    │       │ Amendments /    │       │ OIML R76    │
│ Metrology     │     │ Models Rules   │       │ Notifications   │       │              │
│ Framework     │     │                │       │                 │       │ Technical    │
│               │     │ Model approval │       │ Updated rules   │       │ requirements│
│ LM Act 2009   │     │ requirements   │       │ Corrigenda      │       │ Test methods│
│ General Rules │     │                │       │                 │       │ MPE         │
└───────┬───────┘     └───────┬────────┘       └────────┬────────┘       └──────┬───────┘
        │                       │                         │                        │
        └───────────────────────┴────────────┬───────────┴────────────────────────┘
                                             │
                                             ▼
                           ┌─────────────────────────────────┐
                           │     REGULATORY KNOWLEDGE BASE   │
                           │                                 │
                           │ Document ingestion              │
                           │ Text extraction                 │
                           │ Cleaning / Chunking              │
                           │ Metadata                         │
                           │ Clause / Page references         │
                           │ Embeddings                       │
                           │ Vector Database                  │
                           └────────────────┬────────────────┘
                                            │
                                            ▼
                                  ┌───────────────────┐
                                  │   REGULATORY RAG  │
                                  └─────────┬─────────┘
                                            │
                                            ▼
                                  ┌───────────────────┐
                                  │   LLM / AI        │
                                  │ Explanation Layer │
                                  └───────────────────┘



╔══════════════════════════════════════════════════════════════════════════════════════╗
║                              CORE APPLICATION                                       ║
╚══════════════════════════════════════════════════════════════════════════════════════╝

                              ┌─────────────────────┐
                              │  USER LOGIN / AUTH   │
                              └──────────┬──────────┘
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │ RBAC & USER ROLES    │
                              │                     │
                              │ Admin               │
                              │ Test Engineer       │
                              │ Reviewer            │
                              │ Viewer              │
                              └──────────┬──────────┘
                                         │
                                         ▼
                         ┌──────────────────────────────┐
                         │ 1. EQUIPMENT REGISTRATION   │
                         ├──────────────────────────────┤
                         │ Manufacturer                 │
                         │ Model                        │
                         │ Serial Number                │
                         │ Instrument Type               │
                         │ Intended Use                  │
                         │ Max / Min                     │
                         │ e / d                        │
                         │ Accuracy Class                │
                         │ Technical specifications      │
                         └───────────────┬──────────────┘
                                         │
                                         ▼
                  ╔══════════════════════════════════════════════╗
                  ║ 2. LEGAL METROLOGY APPLICABILITY CHECK     ║
                  ╠══════════════════════════════════════════════╣
                  ║                                              ║
                  ║ • Is this instrument within applicable      ║
                  ║   Legal Metrology scope?                    ║
                  ║                                              ║
                  ║ • What Indian rules apply?                  ║
                  ║                                              ║
                  ║ • Is model approval applicable?             ║
                  ║                                              ║
                  ║ • What regulatory requirements apply?       ║
                  ║                                              ║
                  ║ • What technical standard/test basis        ║
                  ║   applies?                                  ║
                  ╚═══════════════════════╤══════════════════════╝
                                          │
                              ┌───────────┴───────────┐
                              │                       │
                            NOT                       YES
                         APPLICABLE                    │
                              │                       ▼
                              ▼          ┌─────────────────────────┐
                    ┌─────────────────┐  │ 3. REQUIREMENT &        │
                    │ REFER / STOP    │  │    TEST DETERMINATION   │
                    │                 │  │                         │
                    │ Instrument does │  │ Applicable Indian       │
                    │ not proceed     │  │ requirements +          │
                    │ through this    │  │ applicable OIML R76     │
                    │ testing flow    │  │ test procedures         │
                    └─────────────────┘  └────────────┬────────────┘
                                                      │
                                                      ▼
                                      ┌────────────────────────────┐
                                      │ 4. DOCUMENT & EVIDENCE     │
                                      │                            │
                                      │ • Nameplate photo          │
                                      │ • Manual                   │
                                      │ • Datasheet                │
                                      │ • Model approval document  │
                                      │ • Certificates             │
                                      │ • Drawings                 │
                                      │ • Instrument photographs   │
                                      └──────────────┬─────────────┘
                                                     │
                                  ┌──────────────────┴──────────────────┐
                                  │                                     │
                                  ▼                                     ▼
                       ┌────────────────────┐              ┌────────────────────┐
                       │ OCR / COMPUTER     │              │ DOCUMENT           │
                       │ VISION             │              │ EXTRACTION         │
                       │                    │              │                    │
                       │ Model              │              │ Manuals            │
                       │ Serial No.         │              │ Datasheets         │
                       │ Max / Min          │              │ Certificates       │
                       │ e / d              │              │ Technical docs    │
                       │ Nameplate          │              │                    │
                       └──────────┬─────────┘              └─────────┬──────────┘
                                  │                                  │
                                  └────────────────┬─────────────────┘
                                                   ▼
                                      ┌──────────────────────────┐
                                      │ 5. DATA VALIDATION       │
                                      │                          │
                                      │ Required fields          │
                                      │ Range checks              │
                                      │ Unit consistency         │
                                      │ Model/serial consistency │
                                      │ Configuration checks     │
                                      └────────────┬─────────────┘
                                                   │
                                                   ▼
                                      ┌──────────────────────────┐
                                      │ 6. TEST SESSION CREATION │
                                      ├──────────────────────────┤
                                      │ Laboratory                │
                                      │ Test engineer             │
                                      │ Date / Time               │
                                      │ Temperature               │
                                      │ Humidity                  │
                                      │ Power supply              │
                                      │ Reference standards      │
                                      │ Test equipment            │
                                      └────────────┬─────────────┘
                                                   │
                                                   ▼
                                ╔════════════════════════════════════╗
                                ║ 7. OIML R76 TEST PROCEDURE        ║
                                ╠════════════════════════════════════╣
                                ║                                    ║
                                ║ Applicable tests determined from  ║
                                ║ instrument classification and      ║
                                ║ applicable requirements.           ║
                                ║                                    ║
                                ║ Examples:                          ║
                                ║ • Weighing performance             ║
                                ║ • Repeatability                   ║
                                ║ • Eccentricity                    ║
                                ║ • Zero / tare                     ║
                                ║ • Influence factors               ║
                                ║ • Temperature-related tests       ║
                                ║ • Other applicable R76 tests       ║
                                ╚══════════════════╤═════════════════╝
                                                   │
                                                   ▼
                                      ┌──────────────────────────┐
                                      │ 8. TEST OBSERVATION      │
                                      │    ENTRY                 │
                                      ├──────────────────────────┤
                                      │ Applied load              │
                                      │ Reference value           │
                                      │ Indication                │
                                      │ Error                     │
                                      │ Repeatability readings    │
                                      │ Eccentricity readings    │
                                      │ Other observations       │
                                      │ Test photographs          │
                                      └────────────┬─────────────┘
                                                   │
                                                   ▼
                                      ┌──────────────────────────┐
                                      │ 9. INPUT VALIDATION      │
                                      ├──────────────────────────┤
                                      │ Missing values             │
                                      │ Invalid units              │
                                      │ Invalid ranges             │
                                      │ Impossible values          │
                                      │ Inconsistent observations  │
                                      └────────────┬─────────────┘
                                                   │
                                                   ▼

                 ╔══════════════════════════════════════════════════════════╗
                 ║            10. OIML R76 RULES ENGINE                   ║
                 ╠══════════════════════════════════════════════════════════╣
                 ║                                                          ║
                 ║ Instrument Characteristics                              ║
                 ║            ↓                                             ║
                 ║ Applicable R76 Test                                      ║
                 ║            ↓                                             ║
                 ║ Load Effective Rule Version                             ║
                 ║            ↓                                             ║
                 ║ Perform Deterministic Calculation                        ║
                 ║            ↓                                             ║
                 ║ Determine Applicable Permissible Error / Criterion       ║
                 ║            ↓                                             ║
                 ║ Compare Actual Result with Requirement                   ║
                 ║            ↓                                             ║
                 ║ Individual Test PASS / FAIL                              ║
                 ║                                                          ║
                 ║        *** NO LLM DECISION-MAKING ***                    ║
                 ╚══════════════════════════════╤═══════════════════════════╝
                                                │
                                                ▼
                                    ┌────────────────────────┐
                                    │ 11. TECHNICAL RESULTS  │
                                    ├────────────────────────┤
                                    │ Actual values           │
                                    │ Calculated errors       │
                                    │ Permissible errors      │
                                    │ Test result             │
                                    │ Rule version            │
                                    │ Evidence                │
                                    └────────────┬───────────┘
                                                 │
                                                 ▼
                           ╔════════════════════════════════════╗
                           ║ 12. REGULATORY / MODEL APPROVAL   ║
                           ║     ASSESSMENT                    ║
                           ╠════════════════════════════════════╣
                           ║                                    ║
                           ║ Technical test results             ║
                           ║             +                      ║
                           ║ Applicable regulatory requirements ║
                           ║             +                      ║
                           ║ Required documents / evidence     ║
                           ║             +                      ║
                           ║ Model approval requirements       ║
                           ║             ↓                      ║
                           ║ Reviewer assessment                ║
                           ╚════════════════════╤═══════════════╝
                                                │
                                                ▼
                                    ┌────────────────────────┐
                                    │ 13. REVIEWER            │
                                    │    ASSESSMENT           │
                                    ├────────────────────────┤
                                    │ Review observations      │
                                    │ Review calculations      │
                                    │ Review evidence          │
                                    │ Review regulatory data   │
                                    │ Review AI explanation    │
                                    │ Approve / Return         │
                                    └────────────┬───────────┘
                                                 │
                                                 ▼
                                    ┌────────────────────────┐
                                    │ 14. REPORT GENERATION   │
                                    ├────────────────────────┤
                                    │ Standardized report     │
                                    │ Instrument details       │
                                    │ Test observations        │
                                    │ Calculations             │
                                    │ PASS / FAIL              │
                                    │ Regulatory information   │
                                    │ Evidence / photos       │
                                    │ Reviewer information     │
                                    └────────────┬───────────┘
                                                 │
                              ┌──────────────────┴──────────────────┐
                              ▼                                     ▼
                    ┌───────────────────┐                 ┌───────────────────┐
                    │ PDF REPORT        │                 │ EDITABLE DOCX     │
                    └─────────┬─────────┘                 └─────────┬─────────┘
                              │                                     │
                              └────────────────┬────────────────────┘
                                               ▼
                                  ┌─────────────────────────┐
                                  │ 15. REPORT REPOSITORY   │
                                  ├─────────────────────────┤
                                  │ Instrument history       │
                                  │ Test reports             │
                                  │ Report versions          │
                                  │ Search / filtering       │
                                  │ Historical results       │
                                  └────────────┬────────────┘
                                               │
                              ┌────────────────┼────────────────┐
                              ▼                ▼                ▼
                     ┌────────────────┐ ┌──────────────┐ ┌───────────────┐
                     │ QR VERIFICATION│ │ DASHBOARD    │ │ AUDIT TRAIL   │
                     │                │ │              │ │               │
                     │ Certificate ID │ │ Tests        │ │ User          │
                     │ Report status  │ │ PASS/FAIL    │ │ Timestamp     │
                     │ Authenticity   │ │ Trends       │ │ Action        │
                     └────────────────┘ │ History      │ │ Old/New value │
                                        └──────────────┘ └───────────────┘
```

### AI / RAG Layer
*Sits alongside the core testing pipeline, not inside the PASS/FAIL calculation.*

```
┌─────────────────────────────────────────────────────────────────────┐
│                    AI / KNOWLEDGE LAYER                             │
└─────────────────────────────────────────────────────────────────────┘

     LEGAL METROLOGY DOCS          OIML DOCS          AMENDMENTS
              │                        │                   │
              └────────────────────────┼───────────────────┘
                                       ▼
                              ┌──────────────────┐
                              │ DOCUMENT         │
                              │ INGESTION        │
                              ├──────────────────┤
                              │ PDF extraction   │
                              │ Cleaning         │
                              │ Chunking         │
                              │ Metadata         │
                              │ Clause / Page    │
                              └────────┬─────────┘
                                       │
                                       ▼
                              ┌──────────────────┐
                              │ EMBEDDING MODEL  │
                              └────────┬─────────┘
                                       │
                                       ▼
                              ┌──────────────────┐
                              │ VECTOR DATABASE  │
                              └────────┬─────────┘
                                       │
                                       ▼
                              ┌──────────────────┐
                              │ REGULATORY RAG   │
                              └────────┬─────────┘
                                       │
                                       ▼
                              ┌──────────────────┐
                              │ LLM              │
                              │                  │
                              │ Explain          │
                              │ Retrieve         │
                              │ Summarize        │
                              │ Answer questions │
                              └────────┬─────────┘
                                       │
                                       ▼
                              GROUNDED RESPONSE
                              + SOURCE EVIDENCE
```

### Historical Report RAG

```
             FINALIZED REPORTS
                    │
                    ▼
          ┌─────────────────────┐
          │ Report Text +       │
          │ Metadata            │
          └──────────┬──────────┘
                     ▼
              Embeddings
                     ▼
          ┌─────────────────────┐
          │ Historical Report   │
          │ Vector Database     │
          └──────────┬──────────┘
                     ▼
             Historical RAG
                     ▼
                    LLM
                     ▼
      "Have similar instruments
       failed this test before?"
```

### Data Storage Architecture

```
                         APPLICATION
                              │
             ┌────────────────┼────────────────┐
             │                │                │
             ▼                ▼                ▼
      ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
      │ PostgreSQL  │  │ Object      │  │ Vector DB   │
      │             │  │ Storage     │  │             │
      │ Users       │  │             │  │ Regulatory  │
      │ Instruments │  │ Photos      │  │ Documents   │
      │ Tests       │  │ Manuals     │  │ Amendments  │
      │ Observations│  │ Certificates│  │ Reports     │
      │ Results     │  │ PDF/DOCX    │  │             │
      │ Rules       │  │ Evidence    │  │             │
      │ Audit logs  │  │             │  │             │
      └─────────────┘  └─────────────┘  └─────────────┘
```

### The Corrected Decision Flow

**Wrong (naive approach):**
```
Registration
     ↓
OIML Test
     ↓
PASS
     ↓
Legal Metrology Check
```

**Correct (this architecture):**
```
                  REGISTRATION
                       │
                       ▼
           LEGAL METROLOGY APPLICABILITY
                       │
             ┌─────────┴─────────┐
             │                   │
            NO                  YES
             │                   │
             ▼                   ▼
       REFER / STOP       DETERMINE APPLICABLE
                          REQUIREMENTS
                                │
                                ▼
                         OIML R76 TEST
                                │
                                ▼
                         TEST OBSERVATIONS
                                │
                                ▼
                       DETERMINISTIC RULES
                           ENGINE
                                │
                                ▼
                         TECHNICAL RESULT
                                │
                                ▼
                     REGULATORY / MODEL
                     APPROVAL ASSESSMENT
                                │
                                ▼
                             REVIEW
                                │
                                ▼
                            REPORT
```

---

## PART B — Point-to-Point Implementation Guide

### 1. User Login / Auth

**Implementation:**
- Spring Security + JWT. On login, issue a token carrying `user_id` + `role`.
- Every downstream service call validates this token before executing.
- No business logic here — this is a pure gate.

**Connects to:** RBAC & User Roles (step 2), and implicitly every step after it, since every action from here on is role-checked.

---

### 2. RBAC & User Roles

**Roles and what they unlock:**

| Role | Can do |
|---|---|
| Administrator | Manage users, laboratories, rule versions, regulatory documents |
| Test Engineer | Register instruments, run applicability checks, create test sessions, enter observations, upload evidence |
| Reviewer | Review calculations/evidence/AI explanations, approve or return reports |
| Viewer | Search and view approved reports only |

**Implementation:** A single `roles` table + `@PreAuthorize` annotations (Spring) on every controller method. This is enforced at every subsequent step — assume every write action is role-checked from here on.

**Connects to:** Equipment Registration (step 3) is the first action a Test Engineer performs after login.

---

### 3. Equipment Registration

**Fields captured:** Manufacturer, Model, Serial Number, Instrument Type, Intended Use, Max/Min capacity, `e`/`d` (scale interval / verification scale interval), Accuracy Class, technical specifications.

**Implementation:**
- `POST /api/instruments` — writes to the `instruments` table.
- This is intentionally **just data capture** — no compliance logic runs here yet. The system does not yet know if this instrument even needs OIML testing.

**Connects to:** Legal Metrology Applicability Check (step 4) — every newly registered instrument must pass through this gate before anything else happens.

---

### 4. Legal Metrology Applicability Check — *the critical gate*

This is the step that was missing from the earlier draft and is architecturally the most important addition.

**Questions the system must answer, in order:**
1. Is this instrument within Legal Metrology scope at all? (e.g., used for trade/commerce, or purely internal/lab use — possibly exempt)
2. Which Indian rules apply (Legal Metrology Act 2009, General Rules 2011, Approval of Models Rules)?
3. Is model approval applicable, or already approved under an existing model?
4. What regulatory requirements follow from the above?
5. What technical standard/test basis applies (OIML R76, which edition)?

**Implementation:**
- This is itself a **rules-based decision**, not an LLM call — a decision table keyed on instrument type, intended use, and declared characteristics (similar structure to the Rules Engine in step 10, evaluating *applicability* rather than *pass/fail*).
- Can optionally call the **Regulatory RAG** in an advisory capacity — e.g., to surface "here's the relevant clause that defines scope" to the Test Engineer — but the actual **NOT APPLICABLE / APPLICABLE** branching decision must be deterministic, for the same reason PASS/FAIL must be deterministic later.
- Store the outcome (`applicable: true/false` + rule set matched) against the instrument record, with a timestamp and rule version used — same auditability treatment as the OIML Rules Engine.

**Branching:**
- **NOT APPLICABLE** → Refer/Stop. Instrument does not proceed through testing. Logged for audit; a Reviewer can still see it in the repository as "not applicable — [reason]."
- **APPLICABLE** → proceeds to Requirement & Test Determination (step 5).

**Why this matters for the pitch:** most competing teams jump straight from registration to OIML testing. Explicitly modeling the applicability decision — deterministic and citable — signals real domain understanding, not just software engineering.

---

### 5. Requirement & Test Determination

**What happens:** Determine the specific applicable Indian requirements **and** OIML R76 test procedures for this instrument's accuracy class, capacity, and type.

**Implementation:**
- A second decision table (or extension of the step-4 rules config), keyed on accuracy class → applicable test set (weighing performance, repeatability, eccentricity, zero/tare, influence factors, temperature tests, etc.).
- Output: a `required_tests` list attached to the test session created later.

**Connects to:** Document & Evidence Collection (step 6), and eventually the OIML R76 Test Procedure (step 9) — this list tells the UI which test forms to render.

---

### 6. Document & Evidence Collection

**Captured:** Nameplate photo, manual, datasheet, model approval document, certificates, drawings, instrument photographs.

**Implementation:**
- File upload endpoint → Object Storage, with metadata rows in PostgreSQL (`attachments` table) linking each file to the instrument/test session.
- Forks into two parallel extraction pipelines (7a, 7b) before validation.

---

### 7a. OCR / Computer Vision

**Extracts from photos:** Model, Serial No., Max/Min, `e`/`d`, other nameplate text.

**Implementation:**
- Sent to a vision/OCR service (the **Vision Module** — advisory/extraction only here, not a compliance decision).
- Extracted text compared against manually entered data from step 3; mismatches flagged, not auto-corrected, surfaced to engineer/reviewer.

### 7b. Document Extraction

**Extracts from documents:** structured data from manuals, datasheets, certificates — e.g., confirming declared Max/Min/accuracy class matches the manufacturer's own datasheet.

**Implementation:** Text extraction (PDF parsing / OCR for scanned certificates) feeding into validation next.

**Both 7a and 7b connect to:** Data Validation (step 8).

---

### 8. Data Validation

**Checks:** Required fields present, range checks, unit consistency, model/serial consistency (OCR-extracted serial vs registered serial), configuration checks (accuracy class vs datasheet).

**Implementation:** A dedicated Validation Service run **before** a test session can be created — a hard gate, not advisory. Failed validation blocks progression with specific error reasons.

**Connects to:** Test Session Creation (step 9) — only validated instrument/document data proceeds.

---

### 9. Test Session Creation

**Captured:** Laboratory, test engineer, date/time, temperature, humidity, power supply, reference standards used, test equipment used.

**Implementation:** `test_sessions` table, one row per testing event, referencing the instrument and the `required_tests` list from step 5. Environmental/context data matters because some OIML R76 tests have temperature/humidity tolerances affecting result validity — worth storing even if unused in MVP calculations, for full auditability later.

**Connects to:** OIML R76 Test Procedure (step 10 in this section's numbering — the actual test forms rendered to the engineer).

---

### 10. OIML R76 Test Procedure

**What happens:** Based on `required_tests` from step 5, the UI renders applicable digital forms — Weighing performance, Repeatability, Eccentricity, Zero/tare, Influence factors, Temperature-related tests, etc.

**Implementation:** Each test type is a distinct frontend form component, but all funnel observations into the same `observations` table structure (test_case_id, applied_load/reference readings, indicated values) — keeps the backend schema uniform even though the UI varies per test type.

**Connects to:** Test Observation Entry (step 11).

---

### 11. Test Observation Entry

**Captured:** Applied load, reference value, indication, error, repeatability readings, eccentricity readings, other observations, test photographs.

**Implementation:** `POST /api/tests/{id}/observations` — raw data capture only, no calculation yet.

**Connects to:** Input Validation (step 12).

---

### 12. Input Validation (second gate, specific to test data)

**Checks:** Missing values, invalid units, invalid ranges, impossible values (e.g., negative load), inconsistent observations (indicated value wildly outside plausible range for applied load).

**Implementation:** Same Validation Service pattern as step 8, applied to observation-level data. Deliberately separate from step 8 — step 8 validates *instrument/document* data, step 12 validates *test observation* data, at different points in time by different actors.

**Connects to:** OIML R76 Rules Engine (step 13) — only validated observations reach the compliance-critical calculation.

---

### 13. OIML R76 Rules Engine — *the core, and the one place with "NO LLM DECISION-MAKING"*

```
Instrument Characteristics
        ↓
Applicable R76 Test
        ↓
Load Effective Rule Version
        ↓
Perform Deterministic Calculation
        ↓
Determine Applicable Permissible Error / Criterion
        ↓
Compare Actual Result with Requirement
        ↓
Individual Test PASS / FAIL
```

**Implementation:**
- Load the effective `standard_version` (JSON-configured rule table, not hardcoded Java conditionals) based on accuracy class + test date.
- Compute observed error, resolve the permissible error band, compare, produce point-level then test-level PASS/FAIL.
- Every result tagged with the exact `rule_version_id` used — powers the What-If Impact Simulator later.

**Hard boundary:** this service has no outbound call to the AI/LLM service. It only writes to `compliance_results` and emits events downstream.

**Connects to:** Technical Results (step 14).

---

### 14. Technical Results

**Captured:** Actual values, calculated errors, permissible errors, test result (per test and overall), rule version used, evidence references.

**Implementation:** Essentially the `compliance_results` record plus its linked `calculations` rows, assembled into a single "technical result package" that everything downstream (regulatory assessment, report generation, AI explanation) consumes.

**Connects to:** Regulatory/Model Approval Assessment (step 15).

---

### 15. Regulatory / Model Approval Assessment

**What happens:** Combines the **technical PASS/FAIL** with **regulatory context** — a technical PASS doesn't automatically mean model approval succeeds; documentation completeness and regulatory requirements (from step 4/5) also matter.

**Inputs combined:**
```
Technical test results
       +
Applicable regulatory requirements
       +
Required documents/evidence (from step 6)
       +
Model approval requirements
       ↓
Reviewer assessment
```

**Implementation:** A composed view/service pulling together the technical result, the applicability decision from step 4, and the evidence checklist status — presented to the Reviewer as one screen. Not a second automated decision layer; an aggregation a human reviews next.

**Connects to:** Reviewer Assessment (step 16).

---

### 16. Reviewer Assessment

**What the Reviewer does:** Reviews observations, calculations, evidence, regulatory data, and the AI explanation (pulled from the AI/RAG layer on demand — "why did this fail," etc.) — then Approves or Returns.

**Implementation:**
- `PATCH /api/reports/{id}/review` with `approve` or `return` + reviewer comments.
- This is the human-in-the-loop checkpoint the whole platform is built around: everything upstream (Rules Engine, AI explanations, applicability check) feeds into this one human decision point, and nothing becomes a final report without it.

**Connects to:** Report Generation (step 17).

---

### 17. Report Generation

**Contents:** Standardized report — instrument details, test observations, calculations, PASS/FAIL, regulatory information, evidence/photos, reviewer information.

**Implementation:** Template-based generation (PDF + editable DOCX in parallel), populated from Technical Results (step 14) + Regulatory Assessment (step 15) + Reviewer sign-off (step 16).

**Recommended addition:** at the point the report is locked, run it through the **Hash-Chain Service** (`SHA-256(content + previous_hash)`) before it's considered final — as part of this step, not bolted on afterward.

**Connects to:** Report Repository (step 18).

---

### 18. Report Repository

**Stores:** Instrument history, test reports, report versions, search/filtering, historical results.

**Fans out to three downstream services:**

**18a. QR Verification** — Certificate ID (distinct from internal report ID), report status, authenticity check. This is also where the **public-facing verification API** should sit: a separate, unauthenticated, read-only, rate-limited endpoint so a consumer scanning a shop scale's QR never touches the internal repository directly.

**18b. Dashboard** — Tests count, PASS/FAIL trends, history charts. Also the natural home for the **Manufacturer/Model Scorecard** differentiator (aggregation by manufacturer/model across all reports).

**18c. Audit Trail** — User, timestamp, action, old/new value, logged independently for every step above from registration through report locking.

---

### 19. AI / Knowledge Layer (sits alongside, not inside, the pipeline)

Explicitly drawn as parallel infrastructure — it must never sit inside the PASS/FAIL path.

```
Legal Metrology Docs + OIML Docs + Amendments
        ↓
Document Ingestion (PDF extraction, cleaning, chunking, metadata, clause/page refs)
        ↓
Embedding Model
        ↓
Vector Database
        ↓
Regulatory RAG
        ↓
LLM (explain / retrieve / summarize / answer)
        ↓
Grounded Response + Source Evidence
```

**Implementation notes:**
- Clause/page metadata retained at ingestion enables click-to-source citation jumps in the report/reviewer UI.
- This layer is called *by* the Reviewer Assessment step (16) and by ad-hoc user questions — it never calls *into* the Rules Engine or Applicability Check to influence their decisions, only *reads* their already-computed outputs to explain them.
- The **Legal Metrology Applicability Check (step 4)** can optionally query this same Regulatory RAG for supporting citations — advisory context alongside a deterministic decision, never a substitute for it.

---

### 20. Historical Report RAG

```
Finalized Reports → Report Text + Metadata → Embeddings →
Historical Report Vector Database → Historical RAG → LLM →
"Have similar instruments failed this test before?"
```

**Implementation:** Triggered on report finalization (step 17/18) — each locked report's text and structured metadata (manufacturer, model, accuracy class, test type, result) gets embedded and indexed. Also the data source for the **Predictive Drift Detection** differentiator, run as a separate scheduled batch job over the same underlying observation history.

---

### 21. Data Storage Architecture

```
APPLICATION
     ├── PostgreSQL: users, instruments, tests, observations, results, rules, audit logs
     ├── Object Storage: photos, manuals, certificates, PDF/DOCX, evidence
     └── Vector DB: regulatory documents, amendments, historical reports
```

Three clearly separated stores, each serving exactly one layer — PostgreSQL for everything transactional/relational (including the applicability decision, technical results, and audit trail), Object Storage for binary evidence, Vector DB for anything retrieved semantically.

---

### 22. The Corrected Decision Flow — Why It Matters

**Wrong (naive approach):**
```
Registration → OIML Test → PASS → Legal Metrology Check
```

**Correct (this architecture):**
```
Registration → Legal Metrology Applicability Check
     │
     ├── NO → Refer/Stop
     └── YES → Determine Applicable Requirements → OIML R76 Test →
               Test Observations → Deterministic Rules Engine →
               Technical Result → Regulatory/Model Approval Assessment →
               Review → Report
```

Checking applicability **first** avoids wasted testing effort on out-of-scope instruments and mirrors how real Legal Metrology officers actually work — decide jurisdiction and applicable requirements before running technical procedures, not after. This is worth stating explicitly in the pitch as a deliberate architectural correction over a naive approach, since it signals real domain understanding rather than just software engineering.
