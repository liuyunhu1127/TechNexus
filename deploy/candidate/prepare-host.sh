#!/usr/bin/env bash
set -euo pipefail

TAILNET_HOST="${1:-}"
ADMIN_EMAIL="${2:-}"
ENV_FILE=/etc/technexus/technexus.env

if [[ ${EUID} -ne 0 ]]; then
  echo "prepare-host.sh must run as root" >&2
  exit 1
fi

if [[ -z "${TAILNET_HOST}" || -z "${ADMIN_EMAIL}" ]]; then
  echo "usage: prepare-host.sh <tailnet-fqdn> <bootstrap-admin-email>" >&2
  exit 2
fi

command -v docker >/dev/null
command -v openssl >/dev/null
docker compose version >/dev/null

install -d -m 0755 /opt/technexus/releases /var/lib/technexus /var/log/technexus
install -d -o 65532 -g 65532 -m 0750 /var/lib/technexus/minio
install -d -m 0700 /etc/technexus

if [[ -e "${ENV_FILE}" ]]; then
  echo "Preserving existing ${ENV_FILE}; no secrets were changed."
  exit 0
fi

umask 077
MYSQL_ROOT_PASSWORD="$(openssl rand -hex 32)"
MYSQL_PASSWORD="$(openssl rand -hex 32)"
JWT_SECRET="$(openssl rand -hex 48)"
MINIO_ROOT_USER="tnroot$(openssl rand -hex 8)"
MINIO_ROOT_PASSWORD="$(openssl rand -hex 32)"
STORAGE_ACCESS_KEY="tnapp$(openssl rand -hex 8)"
STORAGE_SECRET_KEY="$(openssl rand -hex 32)"

{
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "${MYSQL_ROOT_PASSWORD}"
  printf 'MYSQL_DATABASE=technexus\n'
  printf 'MYSQL_USER=tn_app\n'
  printf 'MYSQL_PASSWORD=%s\n' "${MYSQL_PASSWORD}"
  printf 'MINIO_ROOT_USER=%s\n' "${MINIO_ROOT_USER}"
  printf 'MINIO_ROOT_PASSWORD=%s\n' "${MINIO_ROOT_PASSWORD}"
  printf 'TECHNEXUS_DB_URL=jdbc:mysql://mysql:3306/technexus?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC\n'
  printf 'TECHNEXUS_DB_USER=tn_app\n'
  printf 'TECHNEXUS_DB_PASSWORD=%s\n' "${MYSQL_PASSWORD}"
  printf 'TECHNEXUS_JWT_SECRET=%s\n' "${JWT_SECRET}"
  printf 'TECHNEXUS_BOOTSTRAP_ADMIN_EMAIL=%s\n' "${ADMIN_EMAIL}"
  printf 'TECHNEXUS_ALLOWED_ORIGINS=https://%s,https://%s:8443\n' "${TAILNET_HOST}" "${TAILNET_HOST}"
  printf 'MINIO_API_CORS_ALLOW_ORIGIN=https://%s,https://%s:8443\n' "${TAILNET_HOST}" "${TAILNET_HOST}"
  printf 'TECHNEXUS_FORWARD_HEADERS_STRATEGY=framework\n'
  printf 'TECHNEXUS_STORAGE_ENDPOINT=https://%s:10000\n' "${TAILNET_HOST}"
  printf 'TECHNEXUS_STORAGE_INTERNAL_ENDPOINT=http://minio:9000\n'
  printf 'TECHNEXUS_STORAGE_BUCKET=technexus-files\n'
  printf 'TECHNEXUS_STORAGE_REGION=us-east-1\n'
  printf 'TECHNEXUS_STORAGE_ACCESS_KEY=%s\n' "${STORAGE_ACCESS_KEY}"
  printf 'TECHNEXUS_STORAGE_SECRET_KEY=%s\n' "${STORAGE_SECRET_KEY}"
  printf 'TECHNEXUS_CLAMAV_HOST=clamav\n'
  printf 'TECHNEXUS_CLAMAV_PORT=3310\n'
  printf 'TECHNEXUS_AI_ENABLED=false\n'
} >"${ENV_FILE}"

chmod 0600 "${ENV_FILE}"
echo "Created ${ENV_FILE} with mode 0600; secret values were not printed."
