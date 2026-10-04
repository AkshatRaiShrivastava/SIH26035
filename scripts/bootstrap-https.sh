#!/usr/bin/env bash
set -euo pipefail
if [[ -f .env ]]; then set -a; source .env; set +a; fi
: "${DOMAIN:?Set DOMAIN in .env}"; : "${LETSENCRYPT_EMAIL:?Set LETSENCRYPT_EMAIL in .env}"
mkdir -p infrastructure/certbot/www infrastructure/certbot/conf
cp infrastructure/nginx/templates/site.http.conf.template infrastructure/nginx/templates/active.conf.template
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --force-recreate postgres backend frontend rag-service nginx
docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile certbot run --rm certbot certonly --webroot -w /var/www/certbot --email "$LETSENCRYPT_EMAIL" --agree-tos --no-eff-email -d "$DOMAIN" -d "www.$DOMAIN"
cp infrastructure/nginx/templates/site.conf.template infrastructure/nginx/templates/active.conf.template
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart nginx
