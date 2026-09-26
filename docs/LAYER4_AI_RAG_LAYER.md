# LAYER 4 — AI / RAG LAYER
## AI-Assisted Legal Metrology Testing & Compliance Platform
### Builds on: LAYER1_DATA_DOMAIN_MODEL.md, LAYER2_RULES_ENGINE.md, LAYER3_BACKEND_APPLICATION_SERVICES.md

---

## 0. What This Layer Is

This is the Python + FastAPI service that owns everything **explanatory, retrieval-based, or generative** in the platform. It is the *only* layer allowed to call an LLM. It never computes a PASS/FAIL, never writes to `mpe_rule`, `calculation`, or `compliance_result`, and never receives write access to any table Layer 1 marks as part of the deterministic chain (Layer 1 §2, §5).

Everything in this layer answers one of five kinds of question:

1. *"What does the regulation say?"* — Regulatory RAG (§3)
2. *"Why did this specific test fail/pass?"* — Explanation service, grounded in Layer 2/3's already-computed verdict (§4)
3. *"Have similar instruments/models behaved this way before?"* — Historical Report RAG (§5)
4. *"What changed between two versions of a regulation?"* — Amendment Diff Summarizer (§6)
5. *"What does this nameplate say, and does it match the record?"* — Vision/OCR (§7)

Plus a cross-cutting concern: all of the above must work in English and at least one regional language (§8).

**The boundary this layer must never cross (repeating Layer 3 §0's framing from the other side):** this service can be handed a `test_session_id`, a verdict, and a set of calculation results as *input context* to explain — it must never be handed the *authority* to produce or alter that verdict. Every response object emitted by this layer carries an explicit `confidence: "explanatory-only"` (or equivalent) marker, and every citation is traceable to a real chunk with `doc_id`, `page_number`, and `rule_version_id` — never a bare LLM assertion with no retrieval backing it.

---

## 1. Service Breakdown

```
ai_rag_service/
├── main.py                    — FastAPI app, router registration, CORS (internal-only, called from Layer 3)
├── config.py                  — model names, vector DB connection, LLM provider config, env-based
├── ingestion/
│   ├── extract.py             — PDF/DOCX text extraction (Act, Rules, OIML R76, amendments)
│   ├── clean.py                — whitespace/OCR-artifact cleanup, header/footer stripping
│   ├── chunk.py                  — section-aware chunking (respects clause/section boundaries, not fixed-token windows)
│   ├── metadata.py                — attaches doc_id, source_title, section_no, page_no, rule_version_id, char_offset
│   └── embed_and_store.py           — calls embedding model, writes to pgvector via SQLAlchemy
├── retrieval/
│   ├── regulatory_retriever.py — hybrid search (dense + keyword) over regulatory_embedding
│   ├── historical_retriever.py  — semantic search over report_embedding (Historical Report RAG)
│   └── reranker.py                — optional cross-encoder rerank of top-k before hand-off to LLM
├── generation/
│   ├── prompts.py              — versioned prompt templates per endpoint (never string-concatenated ad hoc)
│   ├── explanation_service.py   — §4, grounded failure/pass explanation
│   ├── qa_service.py             — §3, general regulatory Q&A
│   ├── diff_service.py            — §6, amendment diff summarization
│   └── llm_client.py               — thin wrapper around the chosen LLM provider, enforces JSON-schema output
├── vision/
│   ├── ocr_service.py           — nameplate text extraction
│   └── damage_classifier.py      — optional, soft-warning-only image classification
├── i18n/
│   ├── translate.py             — query translation / multilingual embedding routing (§8)
│   └── language_detect.py
├── router/
│   └── query_router.py          — classifies an incoming natural-language query to the right sub-service (mirrors Master Doc §6)
└── schemas/                    — Pydantic request/response models, one per endpoint, all carrying `confidence` + citation fields
```

This service is stateless application logic in front of two data stores it does **not** own the schema of:
- **PostgreSQL / pgvector** — the same database as Layers 1–3, but this layer only reads/writes the `regulatory_document`, `regulatory_embedding`, and `report_embedding` tables (§9). It has no access grant to `mpe_rule`, `calculation`, `compliance_result`, or `hash_chain_entry` — enforce this at the DB role level (`GRANT SELECT, INSERT ON regulatory_embedding, report_embedding TO rag_service_role;` with no grants on the deterministic tables at all), not just by convention in the code.
- **Object storage** — read-only access to the source PDFs/DOCX for ingestion, and to uploaded instrument photos for OCR (§7).

---

## 2. Regulatory Embedding Pipeline (Offline / Batch, Not a Runtime Path)

This mirrors the Master Doc §4 Layer 2 pipeline (`Documents → Extraction → Cleaning → Chunking → Metadata → Embeddings → Vector DB`) and Layer 3 §13's note that this is a one-time/periodic data-prep task, not something a user request triggers synchronously.

### 2.1 Trigger points
- **Initial load**: run once against the provided dataset (Master Doc §21 — Act 2009, Approval of Models Rules 2011+2019, General Rules 2011+amendments, Test Centre Rules 2013+amendments, notifications).
- **Amendment ingestion**: an Admin uploads a new regulatory document via Layer 3's `POST /api/rule-versions` flow (Layer 3 §8) — this layer exposes an internal ingestion endpoint that Layer 3 calls *after* the new `rule_version` row exists, so every embedded chunk can be tagged with a real `rule_version_id` foreign reference from the start, not backfilled later.

### 2.2 Pipeline steps
```
1. Extract   — PDF/DOCX → raw text, page-numbered. Use a layout-aware extractor
               (e.g. pdfplumber / PyMuPDF) so page numbers and section headers survive.
2. Clean     — strip repeated headers/footers, fix OCR ligature artifacts, normalize
               whitespace. Never silently drop numbered clauses — a dropped "(a)/(b)/(c)"
               sub-clause marker breaks the citation trail this whole layer exists for.
3. Chunk     — section/clause-aware, not fixed-token sliding window. A chunk boundary
               should align with a clause or sub-clause boundary wherever the source
               document's structure allows it, so a retrieved chunk is always a coherent,
               independently-citable unit of regulation — never half of one requirement
               and half of the next.
4. Metadata  — attach to every chunk: doc_id, source_title, rule_version_id (FK to
               Layer 1's rule_version table — Layer 1 §3.8), section_no, page_no,
               char_offset_start/end (needed for 14.8 Click-to-Source), effective_from/to
               (denormalized copy of the parent rule_version's dates, for fast filtering).
5. Embed     — sentence-transformers (or an equivalent embedding model) → fixed-dim
               vector per chunk.
6. Store     — INSERT into `regulatory_embedding` (pgvector column) in the same
               transaction as the metadata row, so a chunk can never exist without its
               citation metadata.
```

### 2.3 Why version-aware ingestion matters here specifically
Every chunk is tagged with the `rule_version_id` it belongs to (§2.2 step 4). This is what makes two features possible without extra infrastructure:
- **Version-scoped retrieval** — the Regulatory Q&A service (§3) can filter retrieval to the `rule_version_id` that was active for a given `test_session`, so asking "why did this 2024 test fail" retrieves against the 2024 rule text even after a 2026 amendment supersedes it.
- **Amendment Diff Summarizer** (§6) — retrieving the "same" clause across two `rule_version_id`s is a metadata filter, not a re-embedding or re-extraction step.

---

## 3. Regulatory Q&A Service (RAG Boundary Endpoint)

This is what Layer 3's `RegulatoryQAService` (Layer 3 §9) proxies to. The contract on both sides is intentionally narrow.

```
POST /internal/regulatory-qa/ask
     {
       question: string,
       ruleVersionId?: UUID,        // optional — scopes retrieval to a specific version
       language?: string            // BCP-47, defaults to "en"
     }
     →
     {
       answer: string,
       citedClauses: [
         { docId, sourceTitle, sectionNo, pageNo, ruleVersionId, snippet }
       ],
       confidence: "explanatory-only"
     }
```

### 3.1 Retrieval strategy
- **Hybrid retrieval**: dense vector search (pgvector cosine similarity) combined with keyword/BM25-style filtering on section numbers and defined terms (e.g. "Class III", "verification scale interval") — pure dense retrieval alone under-performs on regulatory text where exact term matches (a class symbol, a section number) matter as much as semantic similarity.
- **Version filtering**: if `ruleVersionId` is supplied, retrieval is restricted to chunks tagged with that version (or its ancestors, if the clause is unchanged across versions — see §6.1). If omitted, defaults to the currently active version (`effective_to IS NULL`, mirroring Layer 1 §3.8 / Layer 3 §8).
- **Top-k + rerank**: retrieve top-15 by hybrid score, rerank with a cross-encoder to top-4–6 before passing to the LLM — keeps the prompt grounded in a small, high-precision context window rather than diluting it with marginal matches.

### 3.2 Generation constraints
- The LLM prompt (`generation/prompts.py`) is explicit: *answer only from the provided clauses; if the retrieved context does not contain the answer, say so — never fill the gap from general knowledge.* This is enforced structurally, not just requested: the prompt template includes the retrieved chunks as the only source material and instructs refusal-to-answer as an acceptable output.
- Every sentence of `answer` that asserts a regulatory fact must map back to at least one entry in `citedClauses` — if the LLM's output can't be matched to a retrieved chunk it originated from, the answer is rejected and re-generated once with a stricter prompt, then falls back to a template response ("I found related clauses but couldn't produce a grounded answer — see citations") rather than shipping an ungrounded claim.
- `confidence` is *always* `"explanatory-only"` for this endpoint. There is no code path in this service that returns anything else — this is the field Layer 3's UI contract uses to visibly label the response as non-authoritative (Layer 3 §9).

---

## 4. Grounded Failure/Pass Explanation Service

This is the `"Why did this test fail?"` flow from the Master Doc's Query Router (Master Doc §6) and Example Scenario (Master Doc §7).

```
POST /internal/explanation/test-result
     {
       testSessionId: UUID,
       language?: string
     }
```

### 4.1 What this endpoint is allowed to read
- The **already-computed** `compliance_result` (test-level and observation-level), `calculation`, and the `mpe_rule` row that was applied — all passed in by Layer 3's caller as part of the request payload (or fetched read-only by this service from the same DB, but **never recomputed**). This service must render the *same numbers Layer 2 already produced*, never re-derive them independently — two independently-computed "explanations" of the same test that used slightly different logic would be a serious trust bug in a compliance product.
- Retrieved regulatory clauses relevant to the specific accuracy class / load band / MPE tier that governed the observation(s) that failed (via §3's retriever, scoped automatically by the calculation's `accuracy_class_id` and load band — the user doesn't have to phrase a regulatory question themselves for this endpoint).

### 4.2 Output contract
```json
{
  "summary": "Observation at 500 kg exceeded the permissible error for Class III...",
  "perObservationExplanations": [
    {
      "observationId": "...",
      "appliedLoad": 500,
      "observedError": 0.8,
      "mpeApplied": 0.5,
      "verdict": "FAIL",
      "explanation": "...",
      "citedClauses": [ ... ]
    }
  ],
  "confidence": "explanatory-only"
}
```
- Numeric fields (`observedError`, `mpeApplied`, `verdict`) are **echoed from Layer 2's stored output**, not generated by the LLM — the LLM only writes the `explanation` prose around numbers it was handed, never numbers it computed itself. This is the single most important implementation rule in this section.

---

## 5. Historical Report RAG

Semantic search over past reports — the `"Have similar instruments failed this test?"` branch of the Query Router (Master Doc §6).

```
POST /internal/historical-qa/search
     {
       query: string,             // e.g. "similar models that failed at half capacity"
       instrumentModel?: string,
       accuracyClassId?: int,
       limit?: int
     }
     →
     {
       results: [
         { reportId, instrumentModel, manufacturer, testDate, verdict, similarityScore, snippet }
       ],
       confidence: "explanatory-only"
     }
```

### 5.1 What gets embedded
- Not the raw PDF/DOCX bytes — a **structured summary** of each finalized report (instrument model, class, test type, per-observation verdicts, failure reasons, reviewer comments) generated once at report-approval time and embedded into `report_embedding` (§9.2). This keeps the corpus small, current, and free of formatting noise, and means re-embedding is a one-row job triggered by Layer 3's `ReportGenerationService` (Layer 3 §6) calling this layer's `/internal/historical-qa/index` endpoint after a report is finalized — never a nightly full re-scan of every PDF.
- This is a **read-and-summarize, not lookup-and-decide** feature: results returned are past facts ("Model X failed this test on this date for this reason"), never a prediction or recommendation about the current instrument. Predictive claims about *future* behavior belong to Predictive Drift Detection (§5.3 below / Layer 3 §12), a separate and explicitly advisory feature.

### 5.2 Data isolation note
Historical Report RAG only indexes `report`s with `is_final = TRUE` (Layer 1 §3.10) — draft/unreviewed test sessions never enter this corpus, so a search can never surface a not-yet-reviewed engineer's in-progress work as if it were established history.

### 5.3 Feeding Predictive Drift Detection's plain-language layer
Layer 3 §12 notes drift detection is a pure statistical fit (linear regression on `calculation.error_value` over time) that "never influences a live compliance verdict." This layer's only involvement is turning that already-computed risk score and trend into plain language:
```
POST /internal/explanation/drift-summary
     { instrumentId: UUID, riskScore: number, trendSlope: number, projectedDate: date }
     → { explanation: string, confidence: "explanatory-only" }
```
This endpoint receives numbers Layer 3's `PredictiveDriftService` already computed — it does not touch `calculation` rows itself and does not compute the regression. Same rule as §4.2: this layer explains numbers, it never derives them.

---

## 6. Amendment Diff Summarizer

Implements Master Doc §14.4 end to end from the AI side (Layer 3 exposes the structural diff trigger at `GET /api/rule-versions/{id}/diff/{otherId}`, Layer 3 §8 — this layer supplies the natural-language explanation layered on top).

```
POST /internal/amendment-diff/summarize
     {
       oldRuleVersionId: UUID,
       newRuleVersionId: UUID,
       structuralDiff: [ { sectionNo, oldText, newText } ]   // passed in from Layer 3's structural diff
     }
     →
     {
       changes: [
         {
           sectionNo,
           plainLanguageSummary: string,
           oldCitation: { docId, pageNo, ruleVersionId },
           newCitation: { docId, pageNo, ruleVersionId }
         }
       ],
       confidence: "explanatory-only"
     }
```

### 6.1 Division of responsibility
- **Structural diff** (which sections textually changed) is computed by Layer 3, not this layer — it's a deterministic text comparison, not a retrieval or generation task, and keeping it in Layer 3 means the "did this clause change at all" answer never depends on an LLM call (Master Doc §14.4 step 2).
- **This layer's job** is strictly the "explain what changed, in plain language, citing both versions" step (Master Doc §14.4 step 4) — for each changed section handed to it, retrieve the old and new clause text via §2's version-tagged chunks (confirming the retrieved text matches what Layer 3's diff already identified — a consistency check, not a re-diff), then prompt the LLM to summarize the delta with both versions cited.
- If the LLM's summary can't be grounded in the two retrieved clause texts it was given, this endpoint returns the raw old/new text side by side with no generated summary, rather than an ungrounded interpretation of a regulatory change — a wrong summary of a legal amendment is a much worse failure mode than no summary.

---

## 7. Vision-Assisted Evidence Capture (OCR / Nameplate Matching)

Implements Master Doc §14.7, scoped to nameplate OCR as the MVP-priority piece.

```
POST /internal/vision/verify-nameplate
     {
       imageObjectKey: string,        // from Layer 3's instrument photo upload (Layer 3 §3)
       instrumentId: UUID,
       expectedSerialNo: string,
       expectedModelNo: string
     }
     →
     {
       extractedSerialNo: string | null,
       extractedModelNo: string | null,
       serialMatch: boolean,
       modelMatch: boolean,
       damageFlag: { detected: boolean, notes: string } | null,   // optional, soft warning only
       confidence: "advisory-only"
     }
```

### 7.1 Implementation notes
- OCR runs against the uploaded image only — this service is handed an `imageObjectKey` and fetches the image read-only from object storage; it never writes back to the `instrument` table itself. Layer 3 decides what to do with a mismatch (surface it to the Reviewer), same human-in-the-loop pattern as everywhere else in this layer.
- `confidence: "advisory-only"` (distinct label from `"explanatory-only"` used elsewhere) — this result is a suggestion for a Reviewer to check, not an explanation of an already-settled fact, and the two labels should render differently in the Layer 3/frontend contract so a Reviewer doesn't conflate "the AI explained why this failed" with "the AI thinks this photo doesn't match."
- Damage classification (Master Doc §14.7 step 4) is explicitly optional and, per the Master Doc, safe to drop for MVP if OCR alone is time-constrained — implement `damage_classifier.py` as a stub that returns `null` until prioritized.

---

## 8. Multilingual Support

Implements Master Doc §14.9, applied uniformly across §3, §4, and §5 rather than as a separate endpoint — every generation service accepts an optional `language` field.

### 8.1 Approach
```
User query (Hindi/regional) → language_detect (if not supplied) →
    → EITHER translate query to English before retrieval (simpler, faster to implement)
    → OR route to a multilingual embedding model (better retrieval quality, more setup cost)
→ Regulatory/Historical retrieval (source documents remain English-only — Master Doc §14.9
  explicitly scopes this to the conversational layer, not full corpus translation)
→ LLM prompted to respond in the original query language
→ Citations remain in English (the source clause text), with a translated inline snippet
  alongside — never a translated citation presented as if it were the primary source text,
  since a mistranslation of a legal clause is exactly the kind of error this whole layer's
  grounding discipline exists to prevent.
```

### 8.2 Scope for MVP
English + Hindi only, per Master Doc §14.9 — the language selector lives in Layer 3's AI Assistant UI contract, this layer just needs to accept and honor the `language` field on every endpoint in §3–§6.

---

## 9. Data This Layer Owns

Two tables live in the same PostgreSQL instance as Layers 1–3 but are populated and read exclusively by this layer (Layer 1 §7 flags both as explicitly out of Layer 1's scope, enabled via the `pgvector` extension Layer 1 turns on in its build order — Layer 1 §6 step 1).

### 9.1 `regulatory_document` / `regulatory_embedding`
```sql
CREATE TABLE regulatory_document (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(255) NOT NULL,          -- e.g. 'Legal Metrology (General) Rules, 2011'
    rule_version_id     UUID REFERENCES rule_version(id),  -- FK into Layer 1's table
    object_key          VARCHAR(500) NOT NULL,           -- source PDF/DOCX in object storage
    supersedes_doc_id   UUID REFERENCES regulatory_document(id),  -- for §6 amendment lineage
    ingested_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE regulatory_embedding (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES regulatory_document(id),
    rule_version_id     UUID REFERENCES rule_version(id),
    section_no          VARCHAR(50),
    page_no             INTEGER,
    char_offset_start   INTEGER,
    char_offset_end     INTEGER,
    chunk_text          TEXT NOT NULL,
    embedding           VECTOR(384),                     -- dimension depends on chosen model
    effective_from      DATE,
    effective_to        DATE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reg_embedding_version ON regulatory_embedding(rule_version_id);
CREATE INDEX idx_reg_embedding_vector ON regulatory_embedding USING ivfflat (embedding vector_cosine_ops);
```

### 9.2 `report_embedding` (Historical Report RAG, §5)
```sql
CREATE TABLE report_embedding (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id           UUID NOT NULL UNIQUE REFERENCES report(id),  -- FK into Layer 1's table
    summary_text        TEXT NOT NULL,                   -- generated structured summary, not raw PDF text
    embedding           VECTOR(384),
    instrument_model    VARCHAR(150),
    accuracy_class_id   SMALLINT REFERENCES accuracy_class(id),
    verdict             VARCHAR(10),
    indexed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_embedding_vector ON report_embedding USING ivfflat (embedding vector_cosine_ops);
```

**Access control note:** grant this layer's DB role `SELECT` on `rule_version`, `accuracy_class`, and `report` (read-only, for joins/labels) and full read/write only on `regulatory_document`, `regulatory_embedding`, `report_embedding`. No grant of any kind on `mpe_rule`, `observation`, `calculation`, or `compliance_result` — this is the DB-level enforcement of the boundary stated in §0.

---

## 10. Query Router

Implements the Master Doc §6 routing table as this layer's entry classification step, used when Layer 3's AI Assistant UI sends a free-form question rather than calling a specific endpoint directly.

```
POST /internal/query-router/classify
     { question: string }
     → { route: "DATABASE" | "RULES_EXPLANATION" | "REGULATORY_RAG" | "HISTORICAL_RAG", confidence: number }
```

| Query pattern | Route | Handled by |
|---|---|---|
| "Show all failed ABC-1000 reports." | `DATABASE` | Not this layer — routed back to Layer 3's analytics/query endpoints; this layer only classifies, it does not execute SQL lookups itself |
| "Why did this test fail?" | `RULES_EXPLANATION` | §4 |
| "What does the relevant regulation require?" | `REGULATORY_RAG` | §3 |
| "Have similar models failed this test?" | `HISTORICAL_RAG` | §5 |

**Important:** this router only classifies intent — it never executes the routed action itself for the `DATABASE` branch. A natural-language-to-SQL path is explicitly out of scope (see §11); routing a `DATABASE`-classified query means handing it back to Layer 3 with a suggested structured query shape, not this layer running arbitrary SQL against production tables.

---

## 11. What Layer 4 Explicitly Does Not Include

- **No compliance determination of any kind** — no endpoint in this layer returns a PASS/FAIL, adjusts a verdict, or computes a numeric error/tolerance value. Every number that appears in this layer's output was computed by Layer 2 and merely echoed (§4.2).
- **No natural-language-to-SQL query execution** — the Query Router (§10) classifies a `DATABASE`-intent question but does not run it; arbitrary LLM-generated SQL against production tables is a real injection/correctness risk this project explicitly avoids (ties to Master Doc §16's "What NOT to Do").
- **No write access to `mpe_rule`, `observation`, `calculation`, or `compliance_result`** — enforced at the DB role level (§9), not only in application code.
- **No autonomous action** — drift flags, nameplate mismatches, and amendment-diff summaries are all surfaced for a human (Reviewer/Admin) to act on; nothing in this layer triggers a status change, a rejection, or a notification with legal effect on its own.
- **No full regulatory corpus translation** — multilingual support (§8) covers the conversational layer only, not a translated copy of the Act/Rules/OIML R76 themselves.
- **No synchronous ingestion inside a user-facing request path** — embedding a new document (§2) is triggered by an Admin action via Layer 3 and runs as a background job; it is never inline in a request a user is waiting on.

---

## 12. Build Order Inside Layer 4

1. Stand up the two owned tables (§9) via migration, confirm `pgvector` extension is active (already enabled in Layer 1 §6 step 1).
2. Build and run the ingestion pipeline (§2) once against the provided dataset (Master Doc §21) — this is the prerequisite for every other endpoint in this layer, mirroring how Layer 1's seed data (Layer 1 §4) is a prerequisite for Layer 2.
3. Build the Regulatory Q&A service (§3) first — it's the simplest grounded-retrieval loop and the template every other generation service (§4, §5, §6) reuses.
4. Build the Explanation service (§4), since it's the Master Doc's headline demo moment ("Why did this test fail?" — Master Doc §7, §18).
5. Build Historical Report RAG (§5), wiring its indexing hook into Layer 3's `ReportGenerationService` finalize step.
6. Build Amendment Diff Summarizer (§6) — reuses §3's retrieval almost entirely, per Master Doc §14.4's low-effort note.
7. Multilingual support (§8) as a cross-cutting addition once §3–§6 are stable, per Master Doc's stated priority order (Master Doc §15, item 8).
8. Vision/OCR (§7) last, scoped to nameplate-only if time-constrained, per Master Doc §14.7's own effort note and priority ranking (Master Doc §15, item 9).

---

## 13. Next Layer

There is no Layer 5 in this architecture. The **Client Layer (React, Vite, Tailwind, shadcn/ui — Master Doc §8)** sits on top of Layer 3's REST APIs and this layer's endpoints only ever reached indirectly through Layer 3's `RegulatoryQAService` proxy (Layer 3 §9) — the frontend never calls this service directly, which is the final enforcement point of the deterministic-vs-explanatory boundary that runs through all four layers of this document set.
