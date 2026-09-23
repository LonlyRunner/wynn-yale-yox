#!/usr/bin/env bash
set -euo pipefail

cd /opt/wynn-yale-yox

cp systemd/wynn-backend.service /etc/systemd/system/wynn-backend.service
install -d -m 755 /etc/letsencrypt/renewal-hooks/pre /etc/letsencrypt/renewal-hooks/post
install -m 755 certbot-hooks/certbot-pre-renew.sh /etc/letsencrypt/renewal-hooks/pre/wynn-frontend
install -m 755 certbot-hooks/certbot-post-renew.sh /etc/letsencrypt/renewal-hooks/post/wynn-frontend

docker compose --env-file .env up -d mysql
systemctl daemon-reload
systemctl enable wynn-backend

if [[ -f restart-backend ]] || ! systemctl is-active --quiet wynn-backend; then
  systemctl restart wynn-backend
fi
rm -f restart-backend

backend_ready=false
for attempt in {1..24}; do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null; then
    backend_ready=true
    break
  fi
  sleep 5
done
[[ "$backend_ready" == "true" ]]

docker compose --env-file .env up -d --build frontend --remove-orphans
docker exec wynn-yale-yox-frontend-1 \
  wget -qO- http://host.docker.internal:8080/actuator/health >/dev/null

frontend_ready=false
for attempt in {1..15}; do
  if curl -kfsS --resolve wynnyaleyox.me:443:127.0.0.1 \
    https://wynnyaleyox.me/api/public/profile >/dev/null; then
    frontend_ready=true
    break
  fi
  sleep 2
done
[[ "$frontend_ready" == "true" ]]
