# Module 2 — Testing & Compliance Engine
## Legal Metrology Testing & Compliance Platform

---

## 1. Purpose of This Module

Module 2 is the **compliance-critical heart of the platform**. It takes an instrument that Module 1 has already marked `applicability_status = APPLICABLE` and `validation_status = READY`, runs it through the actual OIML R76 testing procedure, and produces a deterministic, auditable PASS/FAIL result.

**This is the one module in the entire system where an LLM must never be involved in the decision.** Everything an LLM later explains (Module 5) is explaining a result this module already computed and locked.

**Module 2 ends the moment a `compliance_results` record exists for a test case** — it does not include report generation or reviewer sign-off (that's Module 3).

---

## 2. Precondition (hard dependency on Module 1)

Module 2 must refuse to start a test session unless, from Module 1:
```
instrument.applicability_status == APPLICABLE
instrument.validation_status   == READY
instrument.required_tests      != empty
```
This is enforced as a **backend guard**, not just a UI restriction — `POST /api/test-sessions` should reject the request with a clear error if these conditions aren't met.

---

## 3. Scope — What's Inside Module 2

```
(from Module 1: instrument READY + APPLICABLE, required_tests[] known)
        ↓
1. Test Session Creation
        ↓
2. OIML R76 Test Procedure (render applicable test forms)
        ↓
3. Test Observation Entry
        ↓
4. Input Validation (observation-level gate)
        ↓
5. OIML R76 Rules Engine
   ├── Load effective rule version
   ├── Perform deterministic calculation
   ├── Determine permissible error / criterion
   ├── Compare actual vs requirement
   └── PASS / FAIL (per point, then per test)
        ↓
6. Technical Results (assembled record)
        ↓
   Handed off to Module 3 (Regulatory Assessment & Reviewer Workflow)
```

---

## 4. Sub-Component 1: Test Session Creation

### 4.1 What it does
Creates the formal record of a testing event — the context in which observations will be taken.

### 4.2 Fields Captured

| Field | Example | Why it matters |
|---|---|---|
| Laboratory | Lab ID (from Module 1's `laboratories`) | Ties the session to an accredited lab |
| Test Engineer | Logged-in user | Accountability |
| Date/Time | Auto-timestamped | Determines which rule version is "effective" |
| Temperature | 24°C | Some OIML tests have environmental tolerances |
| Humidity | 55% | Same as above |
| Power Supply | 230V AC | Relevant for electronic scale tests |
| Reference Standards used | Calibrated test weights, cert. no. | Evidence that reference equipment is itself traceable |
| Test Equipment used | Weight set ID, calibration date | Same |

### 4.3 Implementation
- `POST /api/test-sessions` creates a `test_sessions` row, linked to `instrument_id` and pre-populated with the `required_tests` list carried over from Module 1's Applicability Engine.
- One instrument can have multiple test sessions over its lifetime (e.g., initial verification, re-verification after repair) — always create a new session, never overwrite.

### 4.4 Data Model
```
test_sessions
 ├── id
 ├── instrument_id
 ├── laboratory_id
 ├── test_engineer_id
 ├── session_date
 ├── temperature
 ├── humidity
 ├── power_supply
 ├── reference_standards (JSON)
 ├── test_equipment (JSON)
 ├── required_tests (JSON array, copied from instrument)
 └── status              (IN_PROGRESS / COMPLETED)
```

### 4.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-sessions` | POST | Create a new test session (guarded by Module 1 precondition) |
| `/api/test-sessions/{id}` | GET | Fetch session details + required tests |
| `/api/instruments/{id}/test-sessions` | GET | List all sessions for an instrument (history) |

---

## 5. Sub-Component 2: OIML R76 Test Procedure (Form Rendering)

### 5.1 What it does
Based on the `required_tests` list, presents the correct digital form for each applicable OIML R76 test type.

### 5.2 Test Types to Support (MVP → full scope)

| Test Type | What it measures | MVP priority |
|---|---|---|
| **Weighing Performance (Accuracy)** | Observed error at multiple load points vs permissible error | **Start here — this is the reference example used throughout the project** |
| Repeatability | Consistency of indication across repeated loadings at the same point | Phase 2 |
| Eccentricity | Error when load is applied off-center on the platform | Phase 2 |
| Zero/Tare | Correct zero-setting and tare behavior | Phase 2 |
| Influence Factors | Effect of temperature, voltage, tilt, etc. on indication | Phase 3 |

### 5.3 Implementation
- Each test type is a **distinct frontend form component**, but all of them write to the *same* underlying `observations` table structure — this keeps the backend schema uniform even as the UI varies per test type.
- The form is driven by `test_sessions.required_tests`, not hardcoded per instrument — adding a new test type later means adding a new form component + a new rule set in Sub-Component 5, not restructuring the database.

### 5.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-sessions/{id}/test-cases` | POST | Create a test case within the session (one per required test type) |
| `/api/test-sessions/{id}/test-cases` | GET | List test cases + their status for this session |

### 5.5 Data Model
```
test_cases
 ├── id
 ├── test_session_id
 ├── test_type            (weighing_accuracy / repeatability / eccentricity / zero_tare / influence_factor)
 ├── status               (pending / observations_entered / evaluated)
 └── created_at
```

---

## 6. Sub-Component 3: Test Observation Entry

### 6.1 What it does
Captures the actual raw readings taken during testing — this is data capture only, no calculation happens here.

### 6.2 Fields Captured (varies slightly by test type, but core structure is shared)

| Field | Example (Weighing Performance) |
|---|---|
| Applied Load | 500 kg |
| Reference Value | 500 kg (from calibrated test weights) |
| Indication (instrument reading) | 500.8 kg |
| Repeatability readings | (array, for repeatability tests) |
| Eccentricity readings | (per-corner load, for eccentricity tests) |
| Test photographs | Optional evidence per observation point |

### 6.3 Implementation
- `POST /api/test-cases/{id}/observations` — accepts one or more observation points in a single call (batch entry is common — engineers usually enter a full load series at once).
- Observed error is **not** computed here — that happens only in the Rules Engine (Sub-Component 5), after validation.

### 6.4 Data Model
```
observations
 ├── id
 ├── test_case_id
 ├── applied_load
 ├── reference_value
 ├── indicated_value
 ├── sequence_number       (for repeatability: which repeat number)
 ├── position              (for eccentricity: which corner/position)
 ├── photo_attachment_id (nullable)
 └── recorded_at
```

### 6.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-cases/{id}/observations` | POST | Submit observation(s) |
| `/api/test-cases/{id}/observations` | GET | List observations for a test case |

---

## 7. Sub-Component 4: Input Validation (Observation-Level Gate)

### 7.1 What it does
The second validation gate in the platform (the first was Module 1's instrument-level gate) — this one is specific to test observation data, run immediately before anything reaches the Rules Engine.

### 7.2 Checks Performed
- Missing values (applied load or indicated value absent)
- Invalid units (e.g., mixed kg/g entries in one series)
- Invalid ranges (applied load outside instrument's Min/Max capacity from Module 1)
- Impossible values (negative load, indicated value orders of magnitude off)
- Inconsistent observations (e.g., indicated value that doesn't plausibly correspond to the applied load at all — a data entry error, not a real error reading)
- For repeatability: minimum required number of repeat readings present
- For eccentricity: all required load positions present

### 7.3 Implementation
- Reuses the same `ValidationService` pattern from Module 1, but with a distinct rule set specific to observation data (`ValidationService.validateObservations(testCaseId)`).
- Returns `READY_FOR_EVALUATION` or a list of specific failures.
- **Hard gate:** the `/evaluate` endpoint in Sub-Component 5 refuses to run unless this validation has passed.

### 7.4 Data Model
```
test_cases (extended)
 └── validation_status   (PENDING / READY_FOR_EVALUATION / FAILED)

observation_validation_results
 ├── id
 ├── test_case_id
 ├── check_name
 ├── passed (boolean)
 ├── detail
 └── checked_at
```

### 7.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-cases/{id}/validate` | POST | Run observation-level validation |
| `/api/test-cases/{id}/validation-results` | GET | View detailed pass/fail per check |

---

## 8. Sub-Component 5: OIML R76 Rules Engine — *the core of Module 2*

### 8.1 Golden Rule
**No LLM computes or influences this result. Ever.** This service has no outbound call to any AI/LLM service.

### 8.2 Calculation Flow
```
Instrument Characteristics (accuracy_class, e, from Module 1)
        ↓
Load Effective Rule Version (based on test_session date + accuracy_class)
        ↓
For each observation point:
    observed_error = indicated_value − reference_value
    n = applied_load / e
    Look up matching band in the rule set for this n
    permissible_error = band.multiplier × e
    point_result = PASS if |observed_error| ≤ permissible_error else FAIL
        ↓
Test-level result = PASS only if ALL points PASS
```

### 8.3 Worked Example (reference scenario used throughout the project)

Instrument: ABC-1000, Max 1000 kg, Class III, e = 0.5 kg

| Applied Load | Indicated Value | Observed Error | n = load/e | Permissible Error | Result |
|---|---|---|---|---|---|
| 100 kg | 100.2 kg | +0.2 kg | 200 | ±0.5e = 0.25 kg | PASS |
| 200 kg | 200.1 kg | +0.1 kg | 400 | ±0.5e = 0.25 kg | PASS |
| 500 kg | 500.8 kg | +0.8 kg | 1000 | ±1.0e = 0.50 kg | **FAIL** |
| 1000 kg | 1001.5 kg | +1.5 kg | 2000 | ±1.0e = 0.50 kg | **FAIL** |

**Test-level result: FAIL** (any point failing fails the test).

> Confirm the exact permissible-error band values/edges against the current OIML R76 text for each accuracy class as part of Research Task 1 — the structure above is correct, the numbers must be verified.

### 8.4 Rule Representation — Data, Not Code
Rules are stored as versioned JSON, never hardcoded in Java conditionals:
```json
{
  "standard": "OIML R76",
  "version": "2006_R76-1",
  "accuracy_class": "III",
  "effective_from": "2011-01-01",
  "bands": [
    { "n_min": 0,    "n_max": 500,   "permissible_error_multiplier": 0.5 },
    { "n_min": 500,  "n_max": 2000,  "permissible_error_multiplier": 1.0 },
    { "n_min": 2000, "n_max": 10000, "permissible_error_multiplier": 1.5 }
  ]
}
```
The engine loads the correct `standard_versions` record at evaluation time by matching `accuracy_class` + the latest version whose `effective_from` ≤ the test session date. This is exactly what makes the **What-If Impact Simulator** (Module 5 differentiator) possible later — old observations can be replayed against a different version without ever touching the original result.

### 8.5 Implementation
- `POST /api/test-cases/{id}/evaluate` is the **only** endpoint permitted to write to `calculations` and `compliance_results`. No other path in the system may set a PASS/FAIL value directly.
- On success, emits events to: Audit Trail (Module 4), and marks the test case `status = evaluated`.
- Once all `required_tests` for a session are evaluated, the session itself is marked `COMPLETED` and handed to Module 3.

### 8.6 Data Model
```
standard_versions
 ├── id
 ├── standard_name         ("OIML R76")
 ├── version_label
 ├── accuracy_class
 ├── effective_from
 └── rule_table (JSON)

calculations
 ├── id
 ├── observation_id
 ├── observed_error
 ├── n_value
 ├── permissible_error
 ├── point_result           (PASS/FAIL)
 └── calculated_at

compliance_results
 ├── id
 ├── test_case_id
 ├── final_result            (PASS/FAIL)
 ├── rule_version_id         ← immutable record of exactly which rule set was applied
 └── evaluated_at
```

### 8.7 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-cases/{id}/evaluate` | POST | Run the Rules Engine (only write path to results) |
| `/api/test-cases/{id}/result` | GET | Fetch calculated result (points + final PASS/FAIL) |
| `/api/rules/versions` | GET | List available rule versions (admin) |
| `/api/rules/versions` | POST | Add a new rule version (admin only) |

---

## 9. Sub-Component 6: Technical Results (assembly)

### 9.1 What it does
Bundles everything the Rules Engine produced into a single "technical result package" — this is what Module 3 (Regulatory Assessment) and Module 5 (AI explanation) both consume, so it needs to be a clean, complete, single response object.

### 9.2 Contents
```
{
  test_session_id,
  instrument_id,
  test_cases: [
    {
      test_type,
      observations: [...],
      calculations: [...],
      final_result: PASS/FAIL,
      rule_version_used
    }
  ],
  overall_session_result: PASS/FAIL (all test_cases must PASS)
}
```

### 9.3 API Endpoint

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-sessions/{id}/technical-results` | GET | Fetch the assembled result package for the whole session |

---

## 10. End-to-End Module 2 Flow (Summary)

1. Instrument arrives from Module 1 with `APPLICABLE` + `READY` status and a `required_tests` list.
2. Engineer creates a Test Session (lab, environment, equipment context).
3. For each required test type, a test case is created and the matching digital form is rendered.
4. Engineer enters observations (batch or one at a time).
5. Observation-level validation runs — must pass before evaluation is allowed.
6. Rules Engine evaluates each test case: computes error, looks up permissible error, compares, produces PASS/FAIL — tagged with the exact rule version used.
7. Once all required test cases are evaluated, the session is marked `COMPLETED` and the assembled Technical Results package is handed to Module 3.

---

## 11. Module 2 — Complete API Surface

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/test-sessions` | POST | Create test session |
| `/api/test-sessions/{id}` | GET | Session detail |
| `/api/instruments/{id}/test-sessions` | GET | Session history for an instrument |
| `/api/test-sessions/{id}/test-cases` | POST/GET | Create/list test cases |
| `/api/test-cases/{id}/observations` | POST/GET | Submit/list observations |
| `/api/test-cases/{id}/validate` | POST | Observation-level validation |
| `/api/test-cases/{id}/validation-results` | GET | Validation detail |
| `/api/test-cases/{id}/evaluate` | POST | Run Rules Engine (sole write path to results) |
| `/api/test-cases/{id}/result` | GET | Test case result |
| `/api/test-sessions/{id}/technical-results` | GET | Full assembled result package |
| `/api/rules/versions` | GET/POST | Manage rule versions (admin) |

---

## 12. Tech Stack for This Module

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot |
| Database | PostgreSQL |
| Rule storage | JSON columns / JSONB in PostgreSQL — no separate rules-engine product needed for MVP (evaluate Drools only if rule complexity grows significantly) |
| Frontend | React forms per test type, sharing a common observation-entry component pattern |

---

## 13. MVP Build Order Within Module 2

1. Test Session creation (basic CRUD, guarded by Module 1 precondition check).
2. **Weighing Performance test only** — one test case type, to prove the full pipeline end-to-end.
3. Observation entry for this one test type.
4. Observation-level validation — start with the 3 highest-value checks (missing values, out-of-range load, unit consistency).
5. Rules Engine: seed **one** `standard_versions` record (Class III weighing accuracy) as JSON, implement the calculation loop, wire `/evaluate`.
6. Verify against the worked example in Section 8.3 — this is your first true end-to-end demo moment.
7. Only after this works: add Repeatability and Eccentricity test types, then a second accuracy class, then multiple rule versions (to unlock the What-If Simulator later without rework).

**Definition of done for Module 2:** a `READY`+`APPLICABLE` instrument can have a full test session run against it — observations entered, validated, evaluated by the deterministic engine — producing a stored, versioned, auditable PASS/FAIL, ready to hand off to Module 3's reviewer workflow.

---

## 14. What NOT to Do in This Module

- ❌ Do not call the LLM/AI service from anywhere inside `/evaluate` or the calculation path.
- ❌ Do not hardcode permissible error tables as Java `if/else` — use the JSON rule structure in Section 8.4.
- ❌ Do not let the frontend compute or display a PASS/FAIL that didn't come from `/evaluate`.
- ❌ Do not allow observations to be silently edited after evaluation without creating a new calculation record — this breaks auditability and the future report hash-chain (Module 4).
- ❌ Do not skip storing `rule_version_id` on the result — without it, the What-If Simulator and version-aware compliance story both fall apart later.
- ❌ Do not let Module 3 or any report be generated from a test session that isn't `COMPLETED`.
