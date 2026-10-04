# Production Nginx

Nginx is the only public service. It sends `/` to React, `/api/` to Spring Boot, and `/rag/` to FastAPI. Use `site.http.conf.template` only during initial certificate issuance; use `site.conf.template` after Let’s Encrypt has issued the certificate.
