import os
import re
from typing import Any
from pathlib import Path

import httpx
from pypdf import PdfReader
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

app = FastAPI(title="Legal Metrology RAG Service", version="0.0.1")

GROQ_API_URL = os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions")
GROQ_MODEL = os.getenv("GROQ_MODEL", "qwen/qwen3.8-27b")

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, replace with specific origins
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/")
async def root():
    return {"message": "Legal Metrology RAG Service is running"}

@app.get("/health")
async def health_check():
    return {"status": "healthy", "provider": "groq", "model": GROQ_MODEL, "configured": bool(os.getenv("GROQ_API_KEY"))}


class Citation(BaseModel):
    document: str
    section: str | None = None
    page: int | None = None
    chunk_id: str | None = None


class AskRequest(BaseModel):
    question: str = Field(min_length=3, max_length=4000)
    context: str = Field(default="", max_length=24000)
    citations: list[Citation] = Field(default_factory=list)


def retrieve_pages(question: str, limit: int = 5) -> tuple[str, list[Citation]]:
    terms = {term.lower() for term in re.findall(r"[A-Za-z0-9]{3,}", question) if term.lower() not in {"what", "which", "this", "that", "does", "for", "the", "and"}}
    matches: list[tuple[int, str, int, str, int]] = []
    source_root = Path(os.getenv("DOCUMENTS_ROOT", "/app/documents/source"))
    for pdf in sorted(source_root.glob("*.pdf")):
        try:
            reader = PdfReader(str(pdf))
            for page_number, page in enumerate(reader.pages, start=1):
                text = (page.extract_text() or "").strip()
                if not text:
                    continue
                score = sum(text.lower().count(term) for term in terms)
                if score:
                    matches.append((score, pdf.name, page_number, text, len(text)))
        except Exception:
            continue
    matches.sort(key=lambda match: match[0], reverse=True)
    selected = matches[:limit]
    context = "\n\n".join(f"SOURCE: {name}\nPAGE: {page}\n{text[:6000]}" for _, name, page, text, _ in selected)
    citations = [Citation(document=name, page=page, chunk_id=f"{name}:p{page}") for _, name, page, _, _ in selected]
    return context, citations


@app.get("/ingest")
@app.post("/ingest")
async def ingest() -> dict[str, Any]:
    source_root = Path(os.getenv("DOCUMENTS_ROOT", "/app/documents/source"))
    documents = pages = 0
    for pdf in source_root.glob("*.pdf"):
        documents += 1
        try:
            pages += len(PdfReader(str(pdf)).pages)
        except Exception:
            continue
    return {"documents": documents, "pages": pages, "storage": "source PDFs; page-aware retrieval"}


@app.post("/ask")
async def ask(request: AskRequest) -> dict[str, Any]:
    api_key = os.getenv("GROQ_API_KEY")
    if not api_key:
        return {"answer": "Groq is not configured. Set GROQ_API_KEY in .env and restart the service.", "sources": [], "provider": "groq", "configured": False}

    retrieved_context = request.context
    retrieved_citations = request.citations
    if not retrieved_context:
        retrieved_context, retrieved_citations = retrieve_pages(request.question)

    system_prompt = (
        "You are a regulatory assistant for legal metrology. Answer only from the supplied context. "
        "Do not calculate compliance or invent legal requirements. Clearly say when the context does not "
        "contain an answer. Separate source facts from rules-engine calculations and explanation."
    )
    user_prompt = f"Question:\n{request.question}\n\nRetrieved regulatory context:\n{retrieved_context or '[No matching source text found]'}"
    payload = {
        "model": GROQ_MODEL,
        "temperature": 0,
        "messages": [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}],
    }

    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(GROQ_API_URL, headers={"Authorization": f"Bearer {api_key}"}, json=payload)
            response.raise_for_status()
        body = response.json()
        answer = body["choices"][0]["message"]["content"]
        return {"answer": answer, "sources": [citation.model_dump() for citation in retrieved_citations], "provider": "groq", "model": GROQ_MODEL, "configured": True}
    except httpx.HTTPStatusError as exc:
        detail = exc.response.text[:300]
        return {"answer": "The Groq request was rejected. No regulatory conclusion was generated.", "sources": [], "provider": "groq", "error": "GROQ_HTTP_ERROR", "status_code": exc.response.status_code, "detail": detail}
    except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
        return {"answer": "The Groq request failed. No regulatory conclusion was generated.", "sources": [], "provider": "groq", "error": type(exc).__name__}


@app.get("/insights")
async def insights() -> dict[str, Any]:
    source_root = Path(os.getenv("DOCUMENTS_ROOT", "/app/documents/source"))
    documents = []
    total_pages = 0
    empty_pages = 0
    for pdf in sorted(source_root.glob("*.pdf")):
        try:
            reader = PdfReader(str(pdf))
            page_count = len(reader.pages)
            total_pages += page_count
            extracted = sum(bool((page.extract_text() or "").strip()) for page in reader.pages)
            empty_pages += page_count - extracted
            documents.append({"document": pdf.name, "pages": page_count, "textPages": extracted, "status": "READY" if extracted else "OCR_REQUIRED"})
        except Exception as exc:
            documents.append({"document": pdf.name, "pages": 0, "textPages": 0, "status": "UNREADABLE", "error": type(exc).__name__})
    return {"documents": documents, "totalDocuments": len(documents), "totalPages": total_pages, "pagesNeedingOcr": empty_pages, "message": "Document corpus is indexed for page-aware retrieval." if documents else "No regulatory PDFs found in documents/source."}