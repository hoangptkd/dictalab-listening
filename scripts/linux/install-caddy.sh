#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
stage=$(mktemp -d /tmp/dictalab-caddy.XXXXXX)
version=2.11.7
archive="caddy_${version}_linux_amd64.tar.gz"
curl --fail --location --max-time 120 "https://github.com/caddyserver/caddy/releases/download/v${version}/${archive}" -o "$stage/$archive"
curl --fail --location --max-time 60 "https://github.com/caddyserver/caddy/releases/download/v${version}/caddy_${version}_checksums.txt" -o "$stage/checksums.txt"
cd "$stage"
expected=$(grep "${archive}" checksums.txt | tr -d '\r' | awk '{print $1}')
test "${#expected}" -eq 128
printf '%s  %s\n' "$expected" "$archive" | sha512sum --check --status
tar -xzf "$archive" caddy
./caddy version
systemctl stop caddy
install -m 0755 caddy /usr/bin/caddy
caddy validate --config /etc/caddy/Caddyfile
systemctl restart caddy
echo CADDY_INSTALLED_VERIFIED
