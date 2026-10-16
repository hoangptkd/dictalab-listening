#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
if ! id dictalab >/dev/null 2>&1; then useradd --system --home-dir /var/lib/dictalab --shell /usr/sbin/nologin dictalab; fi
install -d -o root -g dictalab -m 0750 /opt/dictalab /opt/dictalab/app /opt/dictalab/tools
install -d -o dictalab -g dictalab -m 0750 /var/lib/dictalab/media /var/lib/dictalab/personal-content /var/lib/dictalab/backups
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk-headless curl unzip ca-certificates python3 caddy
chown -R dictalab:dictalab /var/lib/dictalab
java -version
echo LINUX_RUNTIME_PREPARED
