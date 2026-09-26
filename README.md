# Legal Metrology Compliance Platform

Spring Boot backend and Vite/React demo UI for OIML R-76 NAWI testing. The deterministic Java rules engine is isolated in `com.legalmetrology.rulesengine`; it has no Spring, database, HTTP, or AI dependency. The RAG service is intentionally not connected to it.

## Run

1. Create PostgreSQL database `legal_metrology` with pgvector installed, then set `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD`.
2. `mvn test` and `mvn spring-boot:run`.
3. In `frontend`, run `npm install && npm run dev`.

Use `psql ... -f scripts/validate-schema.sql` against a migrated disposable database for the Layer 1 smoke check.
