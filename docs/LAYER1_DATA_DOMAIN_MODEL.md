# LAYER 1 — DATA & DOMAIN MODEL LAYER
## AI-Assisted Legal Metrology Testing & Compliance Platform
### SIH Problem Statement: Development of a Software Program/Application for Generation of Test Reports for Non-Automatic Weighing Instruments (NAWI) as per OIML R-76

---

## 0. Why Start Here

Every other layer in the architecture (deterministic OIML R-76 rules engine, Spring Boot services, RAG/AI layer, React frontend, QR public verification) reads and writes the same core entities: **Instrument, Test, Observation, Calculation, ComplianceResult, Report, AuditLog, RuleVersion**. If this layer is wrong or incomplete, every layer above it has to be reworked. So Layer 1 is built and frozen first — schema, constraints, domain rules, and seed data — before a single line of business logic is written.

This document is self-contained: a team member with no prior context should be able to read it and stand up the database from scratch.

**Source of truth for domain rules (must be treated as external, versioned reference data, not hardcoded):**
- Legal Metrology Act, 2009 — https://consumeraffairs.gov.in/pages/legal-metrology-act
- Legal Metrology (General) Rules, 2011
- OIML R 76-1 (2006, incl. amendments) — Non-automatic weighing instruments

---

## 1. Domain Primer (what the schema must encode)

Before designing tables, the domain concepts that drive every constraint in this schema:

### 1.1 NAWI Classification (OIML R 76)
Every NAWI is assigned an **accuracy class** at type approval, which determines its tolerance behavior:

| Class | Symbol | Typical use | Min. number of verification scale intervals (n) |
|---|---|---|---|
| Special | I | Reference/lab standards | ≥ 50,000 |
| High | II | Precious metals, pharma | 100 – 100,000 |
| Medium | III | Retail trade, general commerce | 100 – 10,000 |
| Ordinary | IIII | Coarse trade (e.g. bulk goods) | 100 – 1,000 |

### 1.2 Core Metrological Parameters (per instrument, fixed at type approval)
- **Max (Max)** — maximum capacity
- **Min (Min)** — minimum capacity
- **e** — verification scale interval (defines class-based tolerance steps)
- **d** — actual scale interval (smallest readable increment; e ≥ d always)
- **n = Max / e** — number of verification intervals, must fall inside the class's allowed range above

### 1.3 Maximum Permissible Error (MPE) — the core PASS/FAIL rule
OIML R 76 defines MPE in **verification scale intervals (e)**, tiered by load range, and **doubled for in-service (post type-approval) testing** vs. initial verification:

| Load range (in multiples of e) | MPE at initial verification | MPE in-service |
|---|---|---|
| 0 < m ≤ 50,000 e | ± 0.5 e | ± 1.0 e |
| 50,000 e < m ≤ 200,000 e | ± 1.0 e | ± 2.0 e |
| 200,000 e < m | ± 1.5 e | ± 3.0 e |

This table is **not hardcoded in application code** — it is seed/reference data in the `mpe_rule` table (§3.9), versioned so an amendment doesn't require a code deploy.

### 1.4 What a "Test" actually produces
A test session on one instrument produces a sequence of **observations** (load applied → indicated value), each of which is run through the deterministic rules engine to get an **error**, compared against the applicable **MPE**, yielding a **PASS/FAIL per observation**, rolled up into an overall **compliance result** for the test, which is then locked into an immutable **report** and hash-chained for public QR verification.

This chain — Instrument → Test → Observation → Calculation → ComplianceResult → Report → HashChain — is the backbone of the schema.

---

## 2. Entity-Relationship Overview

```
Manufacturer 1───* Instrument 1───* TestSession 1───* Observation 1───1 Calculation
                                        │                                    │
                                        │                                    ▼
                                        │                          ComplianceResult (per observation)
                                        │                                    │
                                        ▼                                    ▼
                                  ComplianceResult (test-level rollup) ──────┘
                                        │
                                        ▼
                                     Report 1───1 HashChainEntry ───1 QRToken
                                        │
                                        ▼
                                    AuditLog (append-only, references every mutation above)

User *───* Role (via UserRole)         RuleVersion 1───* MPERule
Instrument *───1 AccuracyClass          RegulatoryDocument 1───* RegulatoryEmbeddingRef (pgvector, Layer 3)
```

Key design decisions baked into this ER shape:
- **Observation and Calculation are separate tables**, not one — raw field data (what the engineer entered) must never be overwritten by derived data (what the rules engine computed). This preserves auditability and lets the rules engine be re-run/versioned without touching source data.
- **ComplianceResult exists at two granularities** (per-observation and per-test rollup) so a report can show both the detailed table and the final verdict.
- **RuleVersion/MPERule are first-class, versioned tables**, not enum constants, because OIML/Legal Metrology rules are amended over time and every historical report must be reproducible against the rule version active when it was generated.

---

## 3. Table Definitions (PostgreSQL DDL)

### 3.1 `manufacturer`
```sql
CREATE TABLE manufacturer (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_name          VARCHAR(255) NOT NULL,
    registration_no     VARCHAR(100) UNIQUE,          -- Legal Metrology registration/approval number
    address             TEXT,
    country             VARCHAR(100),
    contact_email       VARCHAR(255),
    contact_phone       VARCHAR(50),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.2 `accuracy_class` (reference table, seeded once — see §1.1)
```sql
CREATE TABLE accuracy_class (
    id                  SMALLINT PRIMARY KEY,          -- 1=I, 2=II, 3=III, 4=IIII
    symbol              VARCHAR(10) NOT NULL UNIQUE,    -- 'I','II','III','IIII'
    label               VARCHAR(50) NOT NULL,
    min_n               INTEGER NOT NULL,
    max_n               INTEGER,                        -- NULL = unbounded (class I)
    description         TEXT
);
```

### 3.3 `instrument`
```sql
CREATE TABLE instrument (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    manufacturer_id     UUID NOT NULL REFERENCES manufacturer(id),
    model_no            VARCHAR(150) NOT NULL,
    serial_no           VARCHAR(150) NOT NULL,
    accuracy_class_id   SMALLINT NOT NULL REFERENCES accuracy_class(id),
    max_capacity        NUMERIC(14,4) NOT NULL,         -- Max
    min_capacity        NUMERIC(14,4) NOT NULL,         -- Min
    e_value             NUMERIC(14,6) NOT NULL,         -- verification scale interval
    d_value             NUMERIC(14,6) NOT NULL,         -- actual scale interval, d ≤ e
    n_intervals         INTEGER GENERATED ALWAYS AS (FLOOR(max_capacity / NULLIF(e_value,0))) STORED,
    unit                VARCHAR(10) NOT NULL DEFAULT 'kg',
    type_approval_no    VARCHAR(150),
    photo_object_key    VARCHAR(500),                   -- pointer into object storage
    installed_location  TEXT,
    owner_name          VARCHAR(255),
    status              VARCHAR(30) NOT NULL DEFAULT 'REGISTERED', -- REGISTERED/UNDER_TEST/VERIFIED/REJECTED/DECOMMISSIONED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_d_le_e CHECK (d_value <= e_value),
    CONSTRAINT chk_min_lt_max CHECK (min_capacity < max_capacity),
    UNIQUE (manufacturer_id, serial_no)
);
CREATE INDEX idx_instrument_class ON instrument(accuracy_class_id);
CREATE INDEX idx_instrument_status ON instrument(status);
```

### 3.4 `app_user` / `role` / `user_role` (RBAC — Engineer/Reviewer/Admin/Viewer, matches Client Layer UIs)
```sql
CREATE TABLE app_user (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name           VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL UNIQUE,
    password_hash       VARCHAR(255) NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role (
    id                  SMALLINT PRIMARY KEY,
    name                VARCHAR(50) NOT NULL UNIQUE      -- ENGINEER / REVIEWER / ADMIN / VIEWER
);

CREATE TABLE user_role (
    user_id             UUID NOT NULL REFERENCES app_user(id),
    role_id             SMALLINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);
```

### 3.5 `test_session` (one full test event on one instrument)
```sql
CREATE TABLE test_session (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id       UUID NOT NULL REFERENCES instrument(id),
    engineer_id         UUID NOT NULL REFERENCES app_user(id),
    reviewer_id         UUID REFERENCES app_user(id),
    test_type           VARCHAR(30) NOT NULL,            -- INITIAL_VERIFICATION / IN_SERVICE / REPAIR_VERIFICATION
    rule_version_id     UUID NOT NULL REFERENCES rule_version(id),  -- pins which MPE table applies
    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT',       -- DRAFT/SUBMITTED/REVIEWED/APPROVED/REJECTED
    test_location       TEXT,
    ambient_temp_c      NUMERIC(5,2),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_test_instrument ON test_session(instrument_id);
CREATE INDEX idx_test_status ON test_session(status);
```

### 3.6 `observation` (raw field data — immutable once submitted)
```sql
CREATE TABLE observation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_session_id     UUID NOT NULL REFERENCES test_session(id),
    sequence_no         INTEGER NOT NULL,                -- order within the test
    test_procedure      VARCHAR(50) NOT NULL,            -- ECCENTRICITY/REPEATABILITY/WEIGHING_TEST/DISCRIMINATION/TARE etc.
    applied_load        NUMERIC(14,4) NOT NULL,          -- reference/standard weight applied
    indicated_value     NUMERIC(14,4) NOT NULL,          -- what the instrument displayed
    load_position       VARCHAR(20),                     -- CENTER/CORNER_1..4 (for eccentricity tests)
    recorded_by         UUID NOT NULL REFERENCES app_user(id),
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_locked           BOOLEAN NOT NULL DEFAULT FALSE,   -- true once test_session is SUBMITTED
    UNIQUE (test_session_id, sequence_no)
);
CREATE INDEX idx_obs_session ON observation(test_session_id);
```

### 3.7 `calculation` (derived — output of the deterministic rules engine, never hand-edited)
```sql
CREATE TABLE calculation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    observation_id      UUID NOT NULL UNIQUE REFERENCES observation(id),
    error_value         NUMERIC(14,6) NOT NULL,          -- indicated_value - applied_load
    error_in_e          NUMERIC(14,6) NOT NULL,          -- error_value / instrument.e_value
    applicable_mpe_id   UUID NOT NULL REFERENCES mpe_rule(id),
    mpe_value           NUMERIC(14,6) NOT NULL,          -- resolved MPE for this load band, in same units as error_value
    within_tolerance    BOOLEAN NOT NULL,
    engine_version      VARCHAR(30) NOT NULL,            -- rules-engine build tag, for reproducibility
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.8 `rule_version` and `mpe_rule` (versioned regulatory reference data — see §1.3)
```sql
CREATE TABLE rule_version (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_document     VARCHAR(255) NOT NULL,           -- e.g. 'OIML R76-1:2006 + Amd'
    effective_from      DATE NOT NULL,
    effective_to        DATE,                             -- NULL = currently active
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mpe_rule (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_version_id     UUID NOT NULL REFERENCES rule_version(id),
    accuracy_class_id   SMALLINT NOT NULL REFERENCES accuracy_class(id),
    load_band_min_e     NUMERIC(14,4) NOT NULL,           -- lower bound, in multiples of e
    load_band_max_e     NUMERIC(14,4),                    -- NULL = unbounded upper
    mpe_initial_e       NUMERIC(6,3) NOT NULL,             -- e.g. 0.5
    mpe_in_service_e    NUMERIC(6,3) NOT NULL              -- e.g. 1.0 (typically 2x initial)
);
CREATE INDEX idx_mpe_lookup ON mpe_rule(rule_version_id, accuracy_class_id, load_band_min_e);
```

### 3.9 `compliance_result` (rollup, both per-observation echo and per-test verdict)
```sql
CREATE TABLE compliance_result (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_session_id     UUID NOT NULL REFERENCES test_session(id),
    scope               VARCHAR(20) NOT NULL,             -- 'TEST_LEVEL' or 'OBSERVATION_LEVEL'
    observation_id      UUID REFERENCES observation(id),  -- NULL when scope = TEST_LEVEL
    verdict             VARCHAR(10) NOT NULL,              -- PASS / FAIL
    failure_reason      TEXT,
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_compliance_test ON compliance_result(test_session_id, scope);
```

### 3.10 `report`, `hash_chain_entry` (immutable, tamper-evident report + QR verification)
```sql
CREATE TABLE report (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_session_id     UUID NOT NULL UNIQUE REFERENCES test_session(id),
    report_no           VARCHAR(100) NOT NULL UNIQUE,
    pdf_object_key       VARCHAR(500),
    docx_object_key      VARCHAR(500),
    generated_by        UUID NOT NULL REFERENCES app_user(id),
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_final            BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE hash_chain_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id           UUID NOT NULL UNIQUE REFERENCES report(id),
    content_hash        CHAR(64) NOT NULL,                -- SHA-256 of canonicalized report content
    prev_hash           CHAR(64),                          -- links to previous chain entry (blockchain-style integrity)
    qr_token            VARCHAR(100) NOT NULL UNIQUE,      -- opaque token embedded in the public QR code
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_hash_qr ON hash_chain_entry(qr_token);
```

### 3.11 `audit_log` (append-only; every mutating action across the system writes here)
```sql
CREATE TABLE audit_log (
    id                  BIGSERIAL PRIMARY KEY,
    actor_user_id       UUID REFERENCES app_user(id),
    entity_type         VARCHAR(50) NOT NULL,             -- 'INSTRUMENT'/'TEST_SESSION'/'OBSERVATION'/'REPORT' ...
    entity_id           UUID NOT NULL,
    action              VARCHAR(30) NOT NULL,              -- CREATE/UPDATE/SUBMIT/APPROVE/REJECT/EXPORT
    diff_json           JSONB,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
```

---

## 4. Reference / Seed Data Required Before Any Test Can Run

1. `accuracy_class` — seed the 4 rows from §1.1 (I, II, III, IIII with min_n/max_n).
2. `rule_version` — seed one row for the current OIML R76 + Legal Metrology (General) Rules 2011 baseline, `effective_from` set to the rules' notified date, `effective_to = NULL`.
3. `mpe_rule` — seed the load-band tiers from §1.3 for every accuracy class (OIML R76 defines separate band tables per class; the 3-tier table in §1.3 is the general form — the actual seed must break it down per class using the full R76-1 tables, since class I/II have finer-grained bands than III/IIII).
4. `role` — seed `ENGINEER`, `REVIEWER`, `ADMIN`, `VIEWER`.

Until these four seed sets exist, the rules engine (Layer 2) has nothing to compute against — this is why Layer 1 must be complete and validated before Layer 2 begins.

---

## 5. Constraints & Integrity Rules Worth Calling Out

- **Immutability boundary**: `observation` rows become read-only (`is_locked = TRUE`) the moment a `test_session` moves to `SUBMITTED`. Corrections after that require a new `test_session` (repeat test), never an UPDATE — this is what makes the audit trail and hash chain meaningful.
- **`calculation` is 1:1 with `observation`**, never computed inline into `observation`, so the rules engine can be re-run (e.g. after a rule amendment, for historical re-analysis) without mutating source data.
- **`compliance_result` at TEST_LEVEL is FAIL if any OBSERVATION_LEVEL row is FAIL** — this rollup rule belongs in Layer 2 (rules engine), not as a DB trigger, to keep business logic out of the schema. The schema only stores the result.
- **`report.is_final` + `hash_chain_entry`** are only created together, in the same transaction — a report is never hash-chained before being marked final, and never marked final without being chained (this pairing should be enforced at the service layer, not just documented).
- **Every table that feeds a report carries enough foreign keys to reconstruct the full chain** (instrument → test_session → observation → calculation → compliance_result → report) purely via SQL joins, with no denormalized copies — denormalization for read performance (e.g. a reporting view) comes later as a materialized view, not as schema-level duplication.

---

## 6. Suggested Build Order Inside Layer 1

1. Create database, enable `pgcrypto` (for `gen_random_uuid()`) and `pgvector` extensions (pgvector needed later for Layer 3, but enable now).
2. Run DDL in dependency order: `role`, `accuracy_class`, `rule_version`, `mpe_rule`, `manufacturer`, `app_user`, `user_role`, `instrument`, `test_session`, `observation`, `calculation`, `compliance_result`, `report`, `hash_chain_entry`, `audit_log`.
3. Load seed data (§4) — write this as idempotent seed scripts (`INSERT ... ON CONFLICT DO NOTHING`), not one-off manual inserts, since they'll need to run in every environment (dev/staging/prod).
4. Write and run a validation script that: creates a dummy manufacturer + instrument + test_session + 3 observations, manually computes expected calculation/compliance_result rows, and asserts the schema (constraints, generated columns) behaves as expected — this is the acceptance test for Layer 1 before Layer 2 (rules engine) starts consuming it.
5. Freeze the schema behind a migration tool (Flyway or Liquibase, given the Spring Boot backend) so every future schema change is a tracked migration, not a manual ALTER.

---

## 7. What Layer 1 Explicitly Does Not Include

To keep this layer's scope honest:
- No API endpoints (Layer/backend application layer — Spring Boot services sit on top of this schema)
- No rules engine logic (Layer 2 — reads `mpe_rule`, writes `calculation`/`compliance_result`, but the *logic* lives in code, not SQL)
- No RAG/vector embedding population (Layer 3/AI layer — uses the `pgvector` extension enabled here, but the embedding tables/pipeline are out of scope for Layer 1)
- No object storage wiring (S3-compatible bucket setup for PDFs/DOCX/photos is infra, referenced here only via the `*_object_key` columns)

---

## 8. Next Layer

**Layer 2 — Deterministic OIML R-76 Rules Engine**, built in Java (Spring Boot) or a dedicated Python microservice: consumes `instrument` + `observation` + `mpe_rule`, writes `calculation` + `compliance_result`, and must be independently unit-testable against the R76 tolerance tables without touching the database (pure functions in, DB writes as a separate step) — this is what makes the "deterministic rules engine" auditable and testable end to end, which matters a lot for a legal-metrology compliance system that may be scrutinized.
