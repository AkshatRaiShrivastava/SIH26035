# Legal Metrology Platform

A runnable prototype for testing non-automatic weighing instruments. The current vertical slice covers demo sign-in, instrument selection, a deterministic accuracy calculation, rule-source labelling, review status, and integrity/status indicators.

## Run it

Prerequisites: Docker with Compose, Java 21, Maven, and Node.js 20+ for local builds.

```bash
cp .env.example .env
docker compose up --build
```

Open `http://localhost:5173`. Demo sign-in: `engineer@demo.local` with any non-empty password.

The frontend uses SPA fallback routing, so direct browser navigation to application paths is supported. Service endpoints are separate: backend health is `http://localhost:8080/api/health`, RAG health is `http://localhost:8000/health`, and RAG questions use `POST http://localhost:8000/ask`.

For phone/LAN testing, open the frontend through the laptop address, such as `http://192.168.0.104:5173`. The frontend automatically uses that same hostname for the backend on port `8080` and RAG service on port `8000`, so public verification pages do not try to call `localhost` on the phone.

Check the mounted regulatory PDFs with `curl http://localhost:8000/ingest`. This endpoint accepts `GET` for inspection and `POST` for an explicit ingestion check.

## Groq RAG assistant

Create a free Groq API key at [console.groq.com](https://console.groq.com/), then put it in `.env`:

```bash
GROQ_API_KEY=your_key_here
GROQ_MODEL=llama-3.1-8b-instant
```

The RAG service calls Groq directly over HTTPS. It does not use the OpenAI SDK or require an OpenAI key. The `/ask` endpoint accepts retrieved regulatory context and citations, and instructs the model to avoid unsupported claims and numerical compliance decisions. Without a key, the service remains healthy but returns a configuration message instead of calling an LLM.

Local checks:

```bash
make backend-test
make frontend-build
docker compose config --quiet
```

Services: frontend `5173`, Spring Boot API `8080`, RAG health service `8000`, PostgreSQL/pgvector `5433` on the host (`5432` inside Compose).

QR verification URL: set `PUBLIC_VERIFICATION_URL` to a browser-reachable frontend base, such as `http://192.168.0.104:5173/verification` for LAN access or `https://my-vercel-app.vercel.app/verification` for deployment. QR codes encode the direct URL `/verification/{reportId}`.

## API slice

- `GET /api/health`
- `POST /api/auth/login`
- `GET /api/instruments`
- `POST /api/instruments`
- `GET /api/dashboard`
- `POST /api/tests`
- `GET /api/reports`
- `GET /api/audit`
- `GET /api/audit/verify`
- `GET /api/analytics`
- `GET /api/reports/{reportId}/verify`
- `GET /api/reports/{reportId}/pdf`
- `GET /api/reports/{reportId}/docx`
- `POST /api/tests/{testId}/reverify`
- `GET /health` on the RAG service
- `GET /ingest` on the RAG service
- `POST /ask` on the RAG service

The prototype calculation is deliberately labelled as an OIML R76-1:2006 prototype rule and is isolated in the API response. It is not a substitute for legal certification until the verified source formula and rule version are configured. The current demo store is in memory; PostgreSQL migrations remain the persistence foundation for the next implementation phase.

## Repository layout

`backend/` contains the Spring Boot service and Flyway migrations. `frontend/` contains the React/Vite operator desk. `rag-service/` contains the FastAPI service boundary. `documents/source/` is the local regulatory corpus location and should only contain documents the project is licensed to process. `docs/` contains the domain and architecture notes.