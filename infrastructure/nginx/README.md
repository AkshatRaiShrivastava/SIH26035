# Production Nginx

Nginx is the only public service. It sends `/` to React, `/api/` to Spring Boot, and `/rag/` to FastAPI. `site.http.conf.template` is used only during initial certificate issuance. `site.conf.template` is the immutable HTTPS source; `active.conf.template` is the configuration Nginx reads and is safely switched by the bootstrap script.
