#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-}"
BUILDER_IMAGE='golang@sha256:1a6d4452c65dea36aac2e2d606b01b4a029ec90cc1ae53890540ce6173ea77ac'
MINIO_VERSION='RELEASE.2025-10-15T17-29-55Z'
MC_VERSION='RELEASE.2025-08-13T08-35-41Z'

if [[ -z "${OUTPUT_DIR}" ]]; then
  echo "usage: build-minio.sh <empty-output-directory>" >&2
  exit 2
fi

command -v docker >/dev/null
mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"
test ! -e "${OUTPUT_DIR}/minio"
test ! -e "${OUTPUT_DIR}/mc"

proxy_args=()
if [[ -n "${HTTP_PROXY:-}" ]]; then
  proxy_args+=(--env "HTTP_PROXY=${HTTP_PROXY}")
fi
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  proxy_args+=(--env "HTTPS_PROXY=${HTTPS_PROXY}")
fi
if [[ -n "${NO_PROXY:-}" ]]; then
  proxy_args+=(--env "NO_PROXY=${NO_PROXY}")
fi

docker run --rm \
  --env GOBIN=/out \
  --env CGO_ENABLED=0 \
  "${proxy_args[@]}" \
  --volume "${OUTPUT_DIR}:/out" \
  "${BUILDER_IMAGE}" \
  go install "github.com/minio/minio@${MINIO_VERSION}"

docker run --rm \
  --env GOBIN=/out \
  --env CGO_ENABLED=0 \
  "${proxy_args[@]}" \
  --volume "${OUTPUT_DIR}:/out" \
  "${BUILDER_IMAGE}" \
  go install "github.com/minio/mc@${MC_VERSION}"

sha256sum "${OUTPUT_DIR}/minio" "${OUTPUT_DIR}/mc"
