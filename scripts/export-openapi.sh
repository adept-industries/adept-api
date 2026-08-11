#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_FILE="${PROJECT_DIR}/docs/openapi/adept-api-v1.json"

mkdir -p "${PROJECT_DIR}/docs/openapi"

TEMP_JSON="$(mktemp)"
trap 'rm -f "${TEMP_JSON}"' EXIT

if curl -sSf "http://localhost:8080/v3/api-docs" -o "${TEMP_JSON}" 2>/dev/null; then
  echo "Fetched OpenAPI spec from running server at http://localhost:8080/v3/api-docs"
else
  echo "Running Spring context test to generate OpenAPI spec..."
  (cd "${PROJECT_DIR}" && ./mvnw test -Dtest=OpenApiExportTest -Dspring.profiles.active=test)
  if [[ -f "${OUTPUT_FILE}" ]]; then
    cp "${OUTPUT_FILE}" "${TEMP_JSON}"
  fi
fi

if [[ ! -s "${TEMP_JSON}" ]]; then
  echo "Error: Failed to fetch or generate OpenAPI spec." >&2
  exit 1
fi

jq --sort-keys . "${TEMP_JSON}" > "${OUTPUT_FILE}"

echo "Successfully exported deterministic OpenAPI spec to ${OUTPUT_FILE}"
