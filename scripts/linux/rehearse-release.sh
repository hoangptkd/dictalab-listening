#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
expected=${1:?Verified artifact SHA256 is required}
[[ "$expected" =~ ^[a-fA-F0-9]{64}$ ]]
stage=/home/ubuntu/dictalab-migration
test "$(sha256sum "$stage/dictalab-verified.jar" | cut -d' ' -f1)" = "${expected,,}"
test "$(curl --silent --show-error --max-time 10 --resolve 184.194.89.90:443:127.0.0.1 -o /dev/null -w '%{http_code}' https://184.194.89.90/api/auth/csrf)" = 503
backup=$(mktemp -d /var/lib/dictalab/backups/rehearsal-release.XXXXXX)
chmod 0700 "$backup"
install -m 0600 /opt/dictalab/app/dictalab.jar "$backup/dictalab.jar"
install -m 0600 /etc/dictalab/app.env "$backup/app.env"
switched=false
recover() {
    result=$?
    if (( result != 0 )) && "$switched"; then
        install -o root -g dictalab -m 0640 "$backup/dictalab.jar" /opt/dictalab/app/dictalab.jar
        install -o root -g dictalab -m 0640 "$backup/app.env" /etc/dictalab/app.env
        systemctl reset-failed dictalab
        systemctl restart dictalab
        echo REHEARSAL_OLD_ARTIFACT_RESTARTED >&2
        # Keep additive Flyway migrations; do not destroy data by reverting the database.
    fi
}
trap recover EXIT
systemctl stop dictalab
switched=true
python3 "$stage/configure-cloud.py" --rehearsal-existing
install -o root -g dictalab -m 0640 "$stage/dictalab-verified.jar" /opt/dictalab/app/dictalab.jar
systemctl reset-failed dictalab
systemctl start dictalab
deadline=$((SECONDS+180))
until curl --fail --silent --max-time 5 http://127.0.0.1:18082/actuator/health | grep -q '"status":"UP"'; do
    if (( SECONDS >= deadline )); then echo REHEARSAL_HEALTH_TIMEOUT >&2; exit 1; fi
    sleep 3
done
python3 "$stage/verify-cloud-db.py"
echo "REHEARSAL_VERIFIED_ARTIFACT=$expected"
echo "REHEARSAL_ROLLBACK_SNAPSHOT=$backup"
echo REHEARSAL_ONLY_PRODUCTION_SOURCE_UNCHANGED
