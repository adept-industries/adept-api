#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_FILE="${PROJECT_DIR}/docs/openapi/adept-api-v1.json"
GENERATED_FILE="${PROJECT_DIR}/target/generated-openapi/adept-api-v1.json"

mkdir -p "${PROJECT_DIR}/docs/openapi"

TEMP_JSON="$(mktemp)"
TEMP_OUTPUT="$(mktemp "${PROJECT_DIR}/docs/openapi/.adept-api-v1.json.XXXXXX")"
trap 'rm -f "${TEMP_JSON}" "${TEMP_OUTPUT}"' EXIT

if curl -sSf "http://localhost:8080/v3/api-docs" -o "${TEMP_JSON}" 2>/dev/null; then
  echo "Fetched OpenAPI spec from running server at http://localhost:8080/v3/api-docs"
else
  echo "Running Spring context test to generate OpenAPI spec..."
  rm -f "${GENERATED_FILE}"
  (cd "${PROJECT_DIR}" && ./mvnw test \
    -Dtest=OpenApiContractTest \
    -Dspring.profiles.active=test \
    -Dopenapi.export.path="${GENERATED_FILE}")
  if [[ -f "${GENERATED_FILE}" ]]; then
    cp "${GENERATED_FILE}" "${TEMP_JSON}"
  fi
fi

if [[ ! -s "${TEMP_JSON}" ]]; then
  echo "Error: Failed to fetch or generate OpenAPI spec." >&2
  exit 1
fi

jq --exit-status --sort-keys . "${TEMP_JSON}" > "${TEMP_OUTPUT}"
mv "${TEMP_OUTPUT}" "${OUTPUT_FILE}"

echo "Successfully exported deterministic OpenAPI spec to ${OUTPUT_FILE}"
