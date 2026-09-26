# LAYER 2 — DETERMINISTIC OIML R-76 RULES ENGINE
## AI-Assisted Legal Metrology Testing & Compliance Platform
### Builds on: LAYER1_DATA_DOMAIN_MODEL.md

---

## 0. Why This Layer Exists and Why It's Isolated

This is the legal and technical core of the entire platform. Everything else — the frontend, the report PDFs, the QR verification, even the AI/RAG explanation layer — exists to present or explain what this layer decides. If this layer is wrong, the platform produces legally incorrect compliance certificates.

That's why it's built as **its own isolated module with no side effects**, not scattered across API controllers:

- **Pure functions in, DB writes as a separate step.** The engine takes plain data (instrument spec + observations) and returns plain data (calculations + verdicts). It never queries the database itself and never calls out to another service mid-calculation. A separate orchestration step (in the backend application layer) reads from `observation`/`instrument`/`mpe_rule`, calls the engine, and persists the result into `calculation`/`compliance_result`.
- **Deterministic and versioned.** Same inputs + same `rule_version` → same output, forever, even years later when re-verifying an old report. The engine stamps every result with `engine_version` (see Layer 1 §3.7) so a historical report's numbers can always be reproduced against the exact code that generated them.
- **Independently unit-testable**, with no database, no HTTP, no mocks required — just data in, data out. This is what lets you prove correctness before wiring it into the rest of the system.

---

## 1. Where It Lives

Two viable placements — pick one before writing code, don't let it drift mid-build:

| Option | When to choose it |
|---|---|
| **Java module inside the Spring Boot backend** (e.g. `com.legalmetrology.rulesengine`) | Simpler deployment (one service), easier transactional consistency with the DB writes. Recommended for a hackathon timeline. |
| **Standalone microservice** (Python or Java) called over REST/gRPC by the backend | Only worth it if the AI layer or another consumer needs to call the engine independently. Adds a network hop and a service to deploy for no benefit at this project's scale. |

**Recommendation: build it as a plain Java module/JAR inside the Spring Boot backend**, with a clean internal interface (§4) — so if you *do* need to extract it into a microservice later, it's a lift-and-shift, not a rewrite.

---

## 2. Core Algorithm — What the Engine Actually Computes

For every `observation`, in order:

### Step 1 — Error calculation
```
error_value  = indicated_value − applied_load
error_in_e   = error_value / instrument.e_value
```

### Step 2 — Resolve applicable MPE
The engine must determine *which row* of `mpe_rule` applies, based on:
- the instrument's `accuracy_class_id`
- the **cumulative load band** the `applied_load` falls into, expressed in multiples of e (`applied_load / e_value`)
- whether the `test_session.test_type` is `INITIAL_VERIFICATION` (use `mpe_initial_e`) or `IN_SERVICE` / `REPAIR_VERIFICATION` (use `mpe_in_service_e`)
- the `test_session.rule_version_id` (never assume "current rules" — always resolve against the version pinned on the test session, per Layer 1 §5)

```
load_in_e = applied_load / instrument.e_value

SELECT * FROM mpe_rule
WHERE rule_version_id = :test_session.rule_version_id
  AND accuracy_class_id = :instrument.accuracy_class_id
  AND load_in_e >= load_band_min_e
  AND (load_band_max_e IS NULL OR load_in_e < load_band_max_e)
```
This lookup is a pure data query the orchestration layer performs and passes into the engine as part of its input — the engine itself does not touch the DB (see §4).

```
mpe_value = (test_type == INITIAL_VERIFICATION ? mpe_rule.mpe_initial_e : mpe_rule.mpe_in_service_e) × instrument.e_value
```

### Step 3 — Tolerance comparison
```
within_tolerance = abs(error_value) <= mpe_value
```
**Use `<=`, not `<`.** OIML R76 tolerance is inclusive at the boundary — an error exactly equal to the MPE passes. This is exactly the boundary test case flagged in the test suite (§6) — get this comparator wrong and every boundary-value test fails silently in the wrong direction.

### Step 4 — Rounding rule (critical, commonly gotten wrong)
Per OIML R76, comparisons must respect the instrument's actual readability (`d_value`), not float precision. The engine must:
- Round `error_value` to the same decimal precision as `d_value` **before** comparing to MPE, using round-half-away-from-zero (not banker's rounding, not truncation)
- Never compare raw floating-point differences directly — always go through a fixed-point/BigDecimal comparison (Java: use `BigDecimal` with `MathContext`, never `double`, for every value in this engine — floating-point error at this precision can flip a PASS to a FAIL)

### Step 5 — Per-observation verdict
Write one `compliance_result` row per observation, `scope = OBSERVATION_LEVEL`.

### Step 6 — Test-level rollup
Once all observations in a `test_session` are processed:
```
test_level_verdict = FAIL if ANY observation-level result is FAIL, else PASS
```
Write one additional `compliance_result` row, `scope = TEST_LEVEL`, `observation_id = NULL`.

---

## 3. Procedure-Specific Logic (beyond the basic error/MPE check)

The engine must branch on `observation.test_procedure`, because OIML R76 test procedures aren't just "one load, one reading" — each has its own pass condition layered on top of the base MPE check in §2.

### 3.1 `WEIGHING_TEST` (standard load-point series)
Standard OIML load points: 0, ~¼ Max, ~½ Max, ~¾ Max, Max, then back down through the same points (ascending + descending series). Base MPE check (§2) applies to each point independently. No additional logic beyond §2, but the engine should validate the full expected point set is present before rolling up — a `WEIGHING_TEST` test session missing a required load point should be flagged incomplete, not silently rolled up as PASS.

### 3.2 `ECCENTRICITY` (corner-loading test)
Load applied at center + 4 corners (or per instrument geometry — some platforms have more load-bearing points). Same base MPE check per point (§2), but MPE for eccentricity testing is typically applied at a *single reference load* (often Max/3 or a load specified by instrument geometry) rather than across the full range — the engine needs the applicable load defined per instrument type, not assumed to be the full weighing-test load series. Flag each corner's PASS/FAIL individually; a single corner failing eccentricity fails the whole eccentricity sub-test, which is itself one input into the overall test-level rollup in §2 Step 6.

### 3.3 `REPEATABILITY` (same load, multiple trials)
Same `applied_load` recorded 3+ times (`sequence_no` differs, `applied_load` identical). In addition to each trial passing the base MPE check individually, the engine must also check the **spread across trials**:
```
repeatability_range = max(indicated_value across trials) − min(indicated_value across trials)
repeatability_pass  = repeatability_range <= mpe_value   (same MPE band as the load tested)
```
This is a *second*, independent PASS/FAIL condition on top of the per-trial check — a repeatability sub-test can fail even if every individual trial was within MPE, if the readings are too spread out relative to each other. Store this as its own `compliance_result` row (`test_procedure = REPEATABILITY`, aggregate scope) rather than folding it silently into per-observation results.

### 3.4 `DISCRIMINATION` (small-load sensitivity test)
Checks the instrument detects a small added load beyond the balance point (typically a load of 0.5e–1.5e depending on class, per R76 §5). Pass condition is a *detectable change* in the display, not a tolerance-band comparison — this test procedure returns a boolean detected/not-detected rather than an error-magnitude comparison, so it needs a distinct code path from §2 rather than being forced through the MPE machinery.

### 3.5 `TARE`
If the instrument's tare function is being tested, apply the same base MPE logic (§2) but against the *net* indicated value (indicated minus tare offset) rather than gross — make sure the orchestration layer passes the correct net value, this is a data-prep concern, not new engine logic.

---

## 4. Engine Interface (the contract the rest of the system codes against)

Keep this narrow and typed — this is what makes the engine swappable/testable in isolation.

```java
// Input: everything the engine needs, with zero DB access of its own
public record InstrumentSpec(
    String accuracyClassSymbol,
    BigDecimal maxCapacity,
    BigDecimal minCapacity,
    BigDecimal eValue,
    BigDecimal dValue
) {}

public record ObservationInput(
    UUID observationId,
    String testProcedure,        // WEIGHING_TEST / ECCENTRICITY / REPEATABILITY / DISCRIMINATION / TARE
    BigDecimal appliedLoad,
    BigDecimal indicatedValue,
    String loadPosition          // nullable, used for ECCENTRICITY
) {}

public record MpeLookupResult(
    UUID mpeRuleId,
    BigDecimal mpeValue          // already resolved: band-matched, initial-vs-in-service applied, converted to instrument units
) {}

public record CalculationOutput(
    UUID observationId,
    BigDecimal errorValue,
    BigDecimal errorInE,
    UUID applicableMpeId,
    BigDecimal mpeValue,
    boolean withinTolerance,
    String engineVersion
) {}

public record TestRollupResult(
    String verdict,               // PASS / FAIL
    List<String> failureReasons   // human-readable, e.g. "Observation seq 4 exceeded MPE by 0.3e"
) {}

public interface RulesEngine {
    CalculationOutput evaluateObservation(
        InstrumentSpec instrument,
        ObservationInput observation,
        MpeLookupResult mpe,
        String testType             // INITIAL_VERIFICATION / IN_SERVICE / REPAIR_VERIFICATION
    );

    TestRollupResult rollUpTest(List<CalculationOutput> allCalculationsInSession);
}
```

The `MpeLookupResult` is deliberately passed **in**, already resolved — the engine does not query `mpe_rule` itself (see §0). The orchestration layer (backend service) does the DB lookup described in §2 Step 2 and hands the engine a plain value. This keeps the engine's unit tests free of any database or Spring context.

---

## 5. Rounding & Precision Implementation Notes (Java-specific)

- Use `BigDecimal` for every field in every record above — never `double`/`float`, anywhere in this module.
- Set a fixed `MathContext` (e.g. `MathContext(10, RoundingMode.HALF_UP)`) for all division (`error_in_e`, `n_intervals` derivation) — division is the one operation that silently introduces non-terminating decimals.
- Round `error_value` to `d_value`'s decimal scale using `RoundingMode.HALF_UP` (round-half-away-from-zero) before the tolerance comparison in §2 Step 3 — this is the step most likely to be skipped, and skipping it is the most likely source of a wrong PASS/FAIL near the boundary.
- Never use `==` on BigDecimal for comparisons — `BigDecimal("1.0").equals(BigDecimal("1.00"))` is `false` due to scale. Always use `.compareTo()`.

---

## 6. Unit Test Suite (build this before wiring the engine into any service)

This directly extends the test scenarios already identified for data generation — the engine's unit tests should consume the same synthetic dataset design, with hand-computed expected outputs as ground truth (kept in a spreadsheet or a separate, dead-simple script — never derive "expected" using a code path that shares logic with the engine itself, or a bug in the shared logic won't be caught).

| Test case | Setup | Expected result |
|---|---|---|
| Clean pass | error_value well inside MPE band | `withinTolerance = true` |
| Boundary — exactly at MPE | `abs(error_value) == mpe_value` exactly | `withinTolerance = true` (inclusive boundary, §2 Step 3) |
| Boundary — just over MPE | `abs(error_value) == mpe_value + smallest d_value increment` | `withinTolerance = false` |
| Deliberate fail | error_value clearly beyond MPE | `withinTolerance = false`, correct `failureReasons` text |
| Negative error | indicated_value < applied_load | error handled correctly with sign, `abs()` applied before comparison |
| Load band transition | applied_load sits exactly on a `load_band_min_e` boundary | correct `mpe_rule` row selected (test the lookup, not just the engine, at this boundary) |
| Initial vs in-service, same instrument/load | same instrument, two test sessions with different `test_type` | different `mpe_value` used (in-service ≈ 2× initial), different verdict possible on the same raw numbers |
| Eccentricity — one corner fails | 4 corners + center, one corner exceeds MPE | eccentricity sub-test verdict = FAIL, test-level rollup = FAIL |
| Repeatability — trials individually pass, spread fails | 3 trials each within MPE individually, but range between them exceeds MPE | repeatability_pass = false even though every trial's `withinTolerance = true` |
| Discrimination — detected | small added load produces a display change | detected = true |
| Discrimination — not detected | small added load produces no display change | detected = false, procedure fails |
| Rounding edge case | error_value has more decimal places than `d_value` allows | rounded correctly before comparison, using HALF_UP |
| Rule version pinning | same instrument, two `rule_version` rows in `mpe_rule` (simulate an amendment) | engine uses the `mpe_rule` row matching the `rule_version_id` passed in, not "latest" |
| Test-level rollup — mixed results | 5 observations, 1 fails | `rollUpTest()` returns overall `FAIL` with the failing observation named in `failureReasons` |
| Test-level rollup — all pass | 5 observations, all pass | `rollUpTest()` returns overall `PASS` |
| Incomplete load series | `WEIGHING_TEST` missing a required load point | flagged as incomplete rather than silently passing (§3.1) |

Aim for these to run as a plain JUnit 5 test class with no Spring context (`@ExtendWith` not required) — if a test in this suite needs `@SpringBootTest`, the engine has leaked a dependency it shouldn't have.

---

## 7. Orchestration — How This Plugs Into the Backend (brief, not this layer's scope)

Not built here, but the engine's contract assumes this shape exists one layer up:

```
POST /api/test-sessions/{id}/evaluate   (backend application layer, not Layer 2)
  1. Load instrument, all observations for the session, and resolve the applicable mpe_rule rows
  2. For each observation: build ObservationInput + MpeLookupResult → call engine.evaluateObservation()
  3. Persist each CalculationOutput → `calculation` table, each verdict → `compliance_result` (OBSERVATION_LEVEL)
  4. Call engine.rollUpTest() with all CalculationOutputs → persist TEST_LEVEL `compliance_result`
  5. Write an `audit_log` entry for the evaluation action
  6. Transition `test_session.status` accordingly
```
This whole sequence should run inside a single DB transaction — either every calculation + rollup + audit entry commits together, or none of it does. A partially-evaluated test session is worse than a rejected one.

---

## 8. What Layer 2 Explicitly Does Not Include

- No HTTP endpoints or controllers (orchestration, §7, belongs to the backend application layer)
- No database access — the engine takes data in, returns data out, full stop
- No PDF/report generation (downstream, consumes `compliance_result` after this layer is done)
- No AI/RAG explanation of *why* something failed in natural language — this engine produces structured `failureReasons` strings; turning those into a conversational explanation is the AI/RAG layer's job, not this one's
- No handling of instrument photo/OCR data — vision/OCR is a separate AI-layer concern feeding *into* `observation` creation, upstream of this engine

---

## 9. Next Layer

**Layer 3 — Backend Application Services** (Auth & RBAC, Manufacturer & Instrument Service, Report Generation, Audit Trail Service, Dashboard/Analytics): the Spring Boot services that own the orchestration described in §7, expose the REST API to the Client Layer, and call this rules engine as an internal dependency rather than reimplementing any of its logic.
