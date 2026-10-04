up:
	docker compose up --build

down:
	docker compose down

backend-test:
	cd backend && mvn test

frontend-build:
	cd frontend && npm run build

build: backend-test frontend-build

ingest:
	cd rag-service && python -m app.ingestion.ingest
