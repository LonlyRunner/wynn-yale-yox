#!/usr/bin/env bash
set -euo pipefail
cd /opt/chuxiaoban
test -s .env
test -s server.jar
test -s web/index.html
chmod 600 .env
docker compose --env-file .env up -d postgres redis storage storage-init
docker compose --env-file .env up -d --force-recreate backend web
for attempt in {1..60}; do
  if docker compose exec -T backend wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'; then
    curl -fsS http://127.0.0.1:18081/ >/dev/null
    echo "Chuxiaoban backend and frontend healthy"
    exit 0
  fi
  sleep 3
done
echo "Chuxiaoban health check failed" >&2
exit 1
