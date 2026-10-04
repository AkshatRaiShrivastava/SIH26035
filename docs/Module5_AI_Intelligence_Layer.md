# Module 5 — AI & Intelligence Layer
## Legal Metrology Testing & Compliance Platform

---

## 1. Purpose of This Module

Module 5 is the **explanation, retrieval, and intelligence layer** that sits alongside the core pipeline (Modules 1–4) — never inside it. Every fact this module surfaces (a PASS/FAIL, a regulatory clause, a historical failure pattern) was already computed and stored by an earlier module; Module 5 retrieves, explains, and predicts, but **never decides**.

This is also where nearly all of the project's uniqueness features live — the parts that separate this from "a RAG chatbot for Legal Metrology."

**Golden rule (repeated from Module 2, because it applies here too):** No component in this module may write to `compliance_results`, `applicability_decisions`, or trigger a report lock. Everything here is advisory, consumed by a human at the Reviewer step (Module 3) or by any authenticated user asking a question.

---

## 2. Precondition (dependency on all prior modules)

Module 5 reads from, but never writes to, the data owned by Modules 1–4:
- Regulatory document corpus (ingested independently, not dependent on any test)
- `instruments`, `applicability_decisions` (Module 1)
- `observations`, `calculations`, `compliance_results` (Module 2)
- `reports`, `regulatory_assessments` (Module 3)
- `audit_logs` (Module 4, for context only)

It can be built and demoed with **stub/seed data** even before Modules 1–4 are fully complete, since the Regulatory RAG in particular only depends on the document corpus, not on live test data.

---

## 3. Scope — What's Inside Module 5

```
AUTHORITATIVE DOCUMENTS (Act, Rules, Amendments, OIML R76)
        ↓
1. Document Ingestion Pipeline
        ↓
2. Regulatory RAG (Vector DB + retriever)
        ↓
3. LLM Explanation Layer ←──────────────┐
        ↑                               │
   Query Router ──────────────┬─────────┤
        │                     │         │
        ▼                     ▼         │
4. Historical Report RAG   Rules Engine │
   (from Module 3's         (Module 2,  │
    locked reports)          read-only) │
        │                               │
        ▼                               │
5. Predictive Drift Detection ──────────┘
   (batch, advisory risk score)

Additional Intelligence Features (built on the above):
 ├── 6. Amendment Diff Summarizer
 ├── 7. What-If Impact Simulator
 ├── 8. Vision-Assisted Evidence Cross-Check
 └── 9. Multilingual Layer (Hindi + regional)
```

---

## 4. Sub-Component 1: Document Ingestion Pipeline

### 4.1 What it does
Turns raw regulatory PDFs into searchable, citable, version-aware chunks.

### 4.2 Pipeline Steps
```
Raw Document (Act / Rules / Amendment / OIML R76)
        ↓
PDF/Text Extraction
        ↓
Cleaning (remove headers/footers/page noise)
        ↓
Chunking (by clause/section, not arbitrary character count)
        ↓
Metadata tagging (document name, version, effective_from, page number, clause reference, supersedes_document_id)
        ↓
Embedding (sentence-transformer model)
        ↓
Vector Database storage
```

### 4.3 Implementation
- **Python/FastAPI service**, separate from the Java backend.
- Chunk boundaries should follow the document's own section/clause structure where possible (hierarchical chunking) — this is what makes citations precise later ("Section 12(3)" rather than "somewhere in this 40-page PDF").
- Every chunk stores **page number** and, if extractable, character offset — this is the metadata that later powers click-to-source citation jumps in the UI (Reviewer screen in Module 3, AI Assistant answers here).
- Each document is tagged with `effective_from` and, when it's an amendment, `supersedes_document_id` linking to the version it replaces — this is what the Amendment Diff Summarizer (Sub-Component 6) depends on.

### 4.4 Data Model
```
regulatory_documents
 ├── id
 ├── document_name           ("Legal Metrology Act 2009", "OIML R76 v.2006")
 ├── document_type           (act / rules / amendment / oiml_standard / notification)
 ├── version_label
 ├── effective_from
 ├── supersedes_document_id (nullable)
 ├── storage_path (original PDF)
 └── ingested_at

document_chunks
 ├── id
 ├── document_id
 ├── chunk_text
 ├── page_number
 ├── clause_reference
 ├── embedding_vector
 └── created_at
```

### 4.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/documents` | POST | Ingest a new regulatory document (admin) |
| `/api/ai/documents` | GET | List ingested documents + versions |

---

## 5. Sub-Component 2: Regulatory RAG

### 5.1 What it does
Answers regulatory questions with retrieval grounded in the ingested corpus — every answer returns **Answer + Source document + Clause + Page + Version**.

### 5.2 Retrieval Approach
- **Baseline (MVP):** vector similarity search over `document_chunks`.
- **Stronger (Phase 2 — "Hybrid Retrieval" differentiator):** combine vector search + keyword/BM25 search + metadata filtering (e.g., filter to only currently-effective documents unless a historical query is explicit) + a reranking step — legal documents often contain exact terminology that pure vector search can miss.

### 5.3 Implementation
- `POST /api/ai/regulatory-query` — takes a natural-language question, retrieves top-K relevant chunks, returns them with full citation metadata attached (not yet the LLM-generated answer — that's Sub-Component 3).
- Example questions this must support: "What regulatory requirement applies to this instrument?", "Which provision supports this compliance decision?", "What documentation is required for model approval?"

### 5.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/regulatory-query` | POST | Retrieve relevant clauses + citations for a question |

---

## 6. Sub-Component 3: LLM Explanation Layer + Query Router

### 6.1 What it does
The natural-language front door to the whole AI layer. Routes a question to the right source, then generates a grounded explanation — never inventing compliance figures itself.

### 6.2 Query Router Logic
```
User question
     ↓
Query Router classifies intent:
 ├── Structured/data question ("show all failed ABC-1000 reports")
 │       → route directly to Report Repository (Module 3) — plain DB query, NOT RAG
 ├── "Why did this fail?" question
 │       → pull actual result from compliance_results (Module 2) + cited rule
 │       → retrieve supporting clause from Regulatory RAG (Sub-Component 2)
 ├── "Have similar instruments failed before?" question
 │       → route to Historical Report RAG (Sub-Component 4)
 └── General regulatory question
         → route to Regulatory RAG only
     ↓
Assembled context → LLM → grounded, cited answer
```

### 6.3 Implementation
- `POST /api/ai/ask` — the single entry point the frontend calls; the router internally decides which sub-service(s) to consult.
- The LLM prompt is constructed so the model is instructed to **only** use the retrieved context and computed facts passed to it — never to calculate a permissible error or assert a PASS/FAIL on its own initiative, even if asked directly.
- Response includes both the natural-language explanation and the raw citation metadata, so the frontend can render clickable citations (ties to click-to-source, Section 12 below).

### 6.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/ask` | POST | Main AI Assistant entry point (routes internally) |

---

## 7. Sub-Component 4: Historical Report RAG

### 7.1 What it does
Semantic search over past test reports, enabling queries like "Have similar weighing instruments failed this test before?" or "What were the common failure patterns for Class III instruments?"

### 7.2 Implementation
- Triggered automatically when a report is `LOCKED` in Module 3 — the report's text content plus structured metadata (manufacturer, model, accuracy class, test type, result) is embedded and stored in a **separate vector collection** from the Regulatory RAG.
- Retrieval combines semantic similarity (on report narrative/explanation text) with structured metadata filtering (e.g., "same accuracy class AND same test type") — pure semantic search alone would be too loose for this use case.

### 7.3 Data Model
```
historical_report_embeddings
 ├── id
 ├── report_id (FK to Module 3)
 ├── manufacturer_id
 ├── model_name
 ├── accuracy_class
 ├── test_type
 ├── result
 ├── embedding_vector
 └── indexed_at
```

### 7.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/historical-search` | POST | Semantic + metadata-filtered search over past reports |

---

## 8. Sub-Component 5: Predictive Drift Detection

### 8.1 What it does
Analyzes an instrument's historical observation data across multiple past tests to flag instruments statistically likely to fail their *next* verification — advisory only.

### 8.2 Implementation
- A **scheduled batch job** (not a live API call), run periodically (e.g., nightly).
- For each instrument with 3+ historical test sessions of the same test type, fit a simple linear trend of observed error over time (deliberately a lightweight, explainable statistical model — not a black-box ML model, which would undermine the "explainable compliance" principle).
- Compute a **risk score**: projected error at the next scheduled test date vs. the permissible error threshold for that instrument's class.
- Write the result to a dashboard-facing table as an advisory flag — **never** to `compliance_results`.

### 8.3 Data Model
```
drift_risk_scores
 ├── id
 ├── instrument_id
 ├── test_type
 ├── trend_slope
 ├── projected_error_at_next_test
 ├── risk_level             (LOW / MEDIUM / HIGH)
 ├── explanation_text        (LLM-generated, plain language)
 └── computed_at
```

### 8.4 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/drift-scores` | GET | Fetch current risk scores (feeds Module 4's Dashboard) |
| `/api/ai/drift-scores/recompute` | POST | Manually trigger the batch job (admin, for demo purposes) |

---

## 9. Sub-Component 6: Amendment Diff Summarizer

### 9.1 What it does
When a new regulatory amendment is ingested, generates a plain-language "what changed" summary between it and the version it supersedes.

### 9.2 Implementation
```
New amendment ingested (has supersedes_document_id set)
        ↓
Structural diff: compare chunk-by-chunk against the old version's chunks
        ↓
For each changed clause: retrieve both old and new text via Regulatory RAG
        ↓
Prompt LLM: "Summarize what changed, in plain language, citing both versions"
        ↓
Store + display as a "What Changed" panel on the document version page
```

### 9.3 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/documents/{id}/diff-summary` | GET | Fetch the change summary vs. the superseded version |

---

## 10. Sub-Component 7: What-If Impact Simulator

### 10.1 What it does
Re-evaluates past test results against a newly added rule version to show which historical PASS results would now be borderline or FAIL — the single strongest proof that "version-aware compliance" is real, not just an architectural claim.

### 10.2 Implementation
```
Admin adds a new standard_versions record (Module 2's schema)
        ↓
Admin triggers: POST /api/ai/simulate-impact { new_rule_version_id }
        ↓
Identify all compliance_results whose test_type/accuracy_class
falls under the new rule's scope
        ↓
Pull the ORIGINAL, UNMODIFIED observations for each (Module 2)
        ↓
Re-run through the Rules Engine (Module 2's calculation logic,
called as a pure function — same code, different rule_version_id)
        ↓
Diff: Report ID | Old Result | New Result | Delta
        ↓
Surface on Dashboard as a clearly labeled SIMULATION output
```

### 10.3 Critical Design Note
This sub-component **calls Module 2's Rules Engine calculation function directly**, passing a different `rule_version_id` — it does not reimplement the calculation logic separately. This is exactly why Module 2 was built with the rule version as a parameter to a pure calculation function rather than something baked into the `/evaluate` endpoint's control flow.

The output is written to a separate `impact_simulations` table — it **never** modifies the original `compliance_results` row or its hash chain (Module 4).

### 10.4 Data Model
```
impact_simulations
 ├── id
 ├── new_rule_version_id
 ├── original_compliance_result_id
 ├── original_result
 ├── simulated_result
 ├── delta_description
 └── simulated_at
```

### 10.5 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/simulate-impact` | POST | Run a what-if simulation against a new rule version |
| `/api/ai/simulate-impact/{id}` | GET | Fetch simulation results |

---

## 11. Sub-Component 8: Vision-Assisted Evidence Cross-Check

### 11.1 What it does
Extends Module 1's OCR step with a vision-model pass to flag visible instrument damage, in addition to the nameplate text extraction already covered in Module 1.

### 11.2 Implementation
- Reuses the same photo upload from Module 1 — this sub-component is an **optional enhancement layer** on top of that existing OCR pipeline, not a new upload path.
- A vision-capable model classifies the image for visible damage/corrosion indicators; result surfaced as a soft advisory note, never blocking.
- **Scope for MVP:** treat this as a stretch goal — nameplate OCR (already in Module 1) delivers most of the value; damage classification can be added in Phase 2/3 if time allows.

### 11.3 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/vision/damage-check` | POST | Run damage classification on an uploaded photo |

---

## 12. Sub-Component 9: Multilingual Layer

### 12.1 What it does
Allows the AI Assistant (Sub-Component 3) to be queried and to respond in Hindi (and, time permitting, one additional regional language).

### 12.2 Implementation
- Either translate the incoming query to English before it reaches the Query Router, or use a multilingual embedding model directly against the (English) regulatory corpus.
- The LLM is prompted to respond in the same language the question was asked in, while citations still point to the original English clause text (with a short translated snippet inline).
- **Scope for MVP:** English + Hindi only; do not attempt to translate the regulatory corpus itself — only the conversational layer.

### 12.3 API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/ask` | POST | (extended) accepts a `language` parameter, same endpoint as Sub-Component 3 |

---

## 13. Click-to-Source Citations (UI feature, not a separate service)

Worth calling out explicitly: because Sub-Component 1's ingestion pipeline retains page number + clause metadata per chunk, the frontend can render every citation returned by `/api/ai/ask` or `/api/ai/regulatory-query` as a clickable element that scrolls a PDF viewer to the exact page and highlights the relevant text. This is a frontend + metadata change only — no new backend endpoint required, since the metadata is already present in every RAG response.

---

## 14. End-to-End Module 5 Flow (Summary)

1. Regulatory documents are ingested independently of any test activity, chunked with rich metadata, and embedded.
2. As reports get locked in Module 3, their content is separately embedded into the Historical RAG collection.
3. Any user question hits `/api/ai/ask`, gets classified by the Query Router, and is answered using the appropriate combination of the Rules Engine's stored results (read-only), Regulatory RAG, and/or Historical RAG.
4. In the background, the Drift Detection batch job continuously scores instruments; Admins can trigger What-If simulations after adding new rule versions; new amendments automatically get diff-summarized against what they supersede.
5. None of the above ever writes to `compliance_results`, `applicability_decisions`, or a locked report — every output here is either a direct answer to a question or an advisory flag reviewed by a human elsewhere in the platform.

---

## 15. Module 5 — Complete API Surface

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/ai/documents` | POST/GET | Ingest/list regulatory documents |
| `/api/ai/regulatory-query` | POST | Retrieve clauses + citations |
| `/api/ai/ask` | POST | Main AI Assistant (routed, multilingual) |
| `/api/ai/historical-search` | POST | Semantic search over past reports |
| `/api/ai/drift-scores` | GET | Fetch drift risk scores |
| `/api/ai/drift-scores/recompute` | POST | Manually trigger drift batch job |
| `/api/ai/documents/{id}/diff-summary` | GET | Amendment change summary |
| `/api/ai/simulate-impact` | POST | Run What-If simulation |
| `/api/ai/simulate-impact/{id}` | GET | Fetch simulation results |
| `/api/ai/vision/damage-check` | POST | Damage classification (stretch goal) |

---

## 16. Tech Stack for This Module

| Layer | Choice |
|---|---|
| Service | Python, FastAPI (separate from the Java backend) |
| Embeddings | Sentence Transformers (or equivalent open embedding model) |
| Vector DB | pgvector (preferred — keeps it inside the same PostgreSQL instance as everything else) / Qdrant / Chroma |
| LLM | Any capable hosted LLM API, called via a thin client layer so the model can be swapped without touching the router logic |
| Document processing | PDF/text extraction library + OCR fallback for scanned regulatory documents |
| Communication with Java backend | REST calls both ways — Module 5 reads from the Java backend's read APIs (compliance results, reports) and is called by the Java backend's Reviewer screen |

---

## 17. MVP Build Order Within Module 5

1. **Document Ingestion + Regulatory RAG** first — this can be built and demoed independently of everything else, using only the provided regulatory dataset. Get citation-accurate retrieval working before adding the LLM layer on top.
2. **LLM Explanation Layer + Query Router**, starting with just two routes: "why did this fail" (needs Module 2 data) and general regulatory questions (Regulatory RAG only). Add the Historical RAG route once Module 3 is producing locked reports.
3. **Historical Report RAG** — wire the auto-embedding trigger on report lock.
4. **Click-to-source citations** — cheap frontend addition once ingestion metadata exists; do this early since it visibly upgrades every subsequent demo of the AI layer.
5. **What-If Impact Simulator** — build once Module 2's calculation logic is confirmed as a clean, reusable pure function; this is the single highest-value differentiator, so prioritize it over Drift Detection and Vision.
6. **Amendment Diff Summarizer** — reuses the Regulatory RAG almost entirely, cheap add-on.
7. **Predictive Drift Detection** and **Multilingual Layer** — good Phase 2 additions once core flows are stable.
8. **Vision-Assisted damage check** — lowest priority; nameplate OCR (Module 1) already covers most of the value this direction offers.

**Definition of done for Module 5:** a user can ask "why did this test fail?" and get a grounded, cited explanation; ask "have similar instruments failed before?" and get relevant historical reports; an Admin can add a new rule version and immediately see which past reports would flip under it; and every citation in every answer is clickable back to its exact source clause.

---

## 18. What NOT to Do in This Module

- ❌ Do not let the LLM compute or assert a PASS/FAIL, permissible error, or applicability decision on its own initiative — even if the user explicitly asks it to "just calculate this."
- ❌ Do not let the Query Router bypass the Rules Engine's stored result when answering a "why did it fail" question — always pull the actual stored `compliance_results` row first, then explain it.
- ❌ Do not let the What-If Simulator write to or modify the original `compliance_results` or its hash chain — simulation output is always a separate, clearly labeled record.
- ❌ Do not let Drift Detection or Vision damage checks influence any pass/fail determination — advisory flags only, surfaced to a human.
- ❌ Do not translate or alter the underlying regulatory corpus itself for the Multilingual Layer — only the conversational query/response layer is translated; source citations stay in their original language.
- ❌ Do not skip retaining page/clause metadata during ingestion "to save time" — without it, click-to-source citations and the entire "explainable compliance" story fall apart.
