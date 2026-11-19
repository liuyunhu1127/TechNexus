#!/usr/bin/env bash
set -euo pipefail

RELEASE_DIR="${1:-$(pwd)}"
ENV_FILE=/etc/technexus/technexus.env
MC_BIN="${RELEASE_DIR}/bin/mc"

if [[ ${EUID} -ne 0 ]]; then
  echo "init-minio.sh must run as root" >&2
  exit 1
fi

test -x "${MC_BIN}"
test -f "${RELEASE_DIR}/config/minio-policy.json"
test -f "${ENV_FILE}"

read_env() {
  local key="$1"
  sed -n "s/^${key}=//p" "${ENV_FILE}" | head -n 1
}

MINIO_ROOT_USER="$(read_env MINIO_ROOT_USER)"
MINIO_ROOT_PASSWORD="$(read_env MINIO_ROOT_PASSWORD)"
STORAGE_BUCKET="$(read_env TECHNEXUS_STORAGE_BUCKET)"
STORAGE_ACCESS_KEY="$(read_env TECHNEXUS_STORAGE_ACCESS_KEY)"
STORAGE_SECRET_KEY="$(read_env TECHNEXUS_STORAGE_SECRET_KEY)"

test -n "${MINIO_ROOT_USER}"
test -n "${MINIO_ROOT_PASSWORD}"
test -n "${STORAGE_BUCKET}"
test -n "${STORAGE_ACCESS_KEY}"
test -n "${STORAGE_SECRET_KEY}"

for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:9000/minio/health/ready >/dev/null; then
    break
  fi
  sleep 2
done
curl -fsS http://127.0.0.1:9000/minio/health/ready >/dev/null

MC_CONFIG_DIR="$(mktemp -d /tmp/technexus-mc.XXXXXX)"
trap 'rm -rf "${MC_CONFIG_DIR}"' EXIT

"${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" alias set local http://127.0.0.1:9000 "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
"${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" mb --ignore-existing "local/${STORAGE_BUCKET}" >/dev/null
"${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" anonymous set none "local/${STORAGE_BUCKET}" >/dev/null
if ! "${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" admin user info local "${STORAGE_ACCESS_KEY}" >/dev/null 2>&1; then
  "${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" admin user add local "${STORAGE_ACCESS_KEY}" "${STORAGE_SECRET_KEY}" >/dev/null
fi
"${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" admin policy create local technexus-app "${RELEASE_DIR}/config/minio-policy.json" >/dev/null 2>&1 || true
"${MC_BIN}" --config-dir "${MC_CONFIG_DIR}" admin policy attach local technexus-app --user "${STORAGE_ACCESS_KEY}" >/dev/null

echo "MinIO restricted TechNexus bucket access is ready."
sha256sum "${RELEASE_DIR}/bin/minio" "${MC_BIN}"
