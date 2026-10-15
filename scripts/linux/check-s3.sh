#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
stage=/home/ubuntu/dictalab-migration
test -f "$stage/S3AudioStorageCheck.class"
install -d -o root -g dictalab -m 0750 /opt/dictalab/tools/sdk
unzip -joq /opt/dictalab/app/dictalab.jar 'BOOT-INF/lib/*' -d /opt/dictalab/tools/sdk
install -o root -g dictalab -m 0640 "$stage/S3AudioStorageCheck.class" /opt/dictalab/tools/
backup=$(mktemp -d /var/lib/dictalab/backups/s3-drill.XXXXXX)
chown dictalab:dictalab "$backup"
chmod 0750 "$backup"
# This server is a blocked rehearsal, not the production writer. Avoid a second JVM on its 1 GB RAM.
systemctl stop dictalab
trap 'systemctl start dictalab' EXIT
sudo -u dictalab env AWS_SHARED_CREDENTIALS_FILE=/etc/dictalab/aws-credentials AWS_PROFILE=dictalab-audio \
    DICTALAB_PUBLIC_URL=https://184.194.89.90 S3_RECOVERY_BACKUP_DIR="$backup" \
    java -Xmx96m -cp '/opt/dictalab/tools/sdk/*:/opt/dictalab/tools' S3AudioStorageCheck
sha256sum "$backup/synthetic-audio.wav"
echo "S3_SYNTHETIC_RECOVERY_BACKUP=$backup"
