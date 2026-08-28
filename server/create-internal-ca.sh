#!/usr/bin/env bash
set -euo pipefail

server_ip="${1:-192.168.0.93}"
target="${2:-/etc/samba-password-api/tls}"
install -d -m 700 "$target"

openssl genrsa -out "$target/royal-samba-ca.key" 4096
openssl req -x509 -new -sha256 -days 3650 -key "$target/royal-samba-ca.key" \
  -out "$target/royal-samba-ca.crt" -subj "/CN=Royal Samba Internal CA"
openssl genrsa -out "$target/server.key" 4096
openssl req -new -key "$target/server.key" -out "$target/server.csr" -subj "/CN=$server_ip"
printf 'subjectAltName=IP:%s\nextendedKeyUsage=serverAuth\n' "$server_ip" > "$target/server.ext"
openssl x509 -req -sha256 -days 825 -in "$target/server.csr" -CA "$target/royal-samba-ca.crt" \
  -CAkey "$target/royal-samba-ca.key" -CAcreateserial -out "$target/server.crt" -extfile "$target/server.ext"
rm -f "$target/server.csr" "$target/server.ext" "$target/royal-samba-ca.srl"
chmod 600 "$target"/*.key
chmod 644 "$target"/*.crt
echo "CA pública para instalar no Windows: $target/royal-samba-ca.crt"
