#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
test -r /etc/dictalab/app.env
test -r /etc/dictalab/aws-credentials
chown root:dictalab /opt/dictalab /opt/dictalab/app /opt/dictalab/tools
chmod 0750 /opt/dictalab /opt/dictalab/app /opt/dictalab/tools
install -d -o dictalab -g dictalab -m 0750 /var/lib/dictalab/logs
install -o root -g dictalab -m 0640 /home/ubuntu/dictalab-migration/dictalab.jar /opt/dictalab/app/dictalab.jar
install -o root -g root -m 0644 /home/ubuntu/dictalab-migration/dictalab.service /etc/systemd/system/dictalab.service
install -o root -g root -m 0644 /home/ubuntu/dictalab-migration/Caddyfile /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl reset-failed dictalab.service
systemctl enable dictalab.service
systemctl restart dictalab.service
deadline=$((SECONDS+180))
until curl --fail --silent --show-error --max-time 5 http://127.0.0.1:18082/actuator/health | grep -q '"status":"UP"'; do
    if (( SECONDS >= deadline )); then echo DICTALAB_HEALTH_TIMEOUT >&2; exit 1; fi
    sleep 3
done
echo DICTALAB_LOCAL_HEALTH_UP
systemctl restart caddy
curl --fail --silent --show-error --max-time 15 --resolve 184.194.89.90:443:127.0.0.1 https://184.194.89.90/actuator/health
systemctl --no-pager --plain --type=service --state=running | grep -E 'dictalab|caddy' || true
