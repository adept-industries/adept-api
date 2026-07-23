#!/usr/bin/env bash
set -euo pipefail

API_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIRECTORY="$(cd "$API_DIRECTORY/.." && pwd)"
ENV_FILE="$LOCAL_DIRECTORY/.env"
COMPOSE_FILE="$API_DIRECTORY/infra/local/compose.yaml"
OUTPUT_DIRECTORY="$API_DIRECTORY/docs/database/erd"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Create the shared local .env first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

mkdir -p "$OUTPUT_DIRECTORY"

docker compose \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  up -d postgres

docker run --rm \
  --network adept-local_adept \
  -v "$OUTPUT_DIRECTORY:/output" \
  schemaspy/schemaspy:7.0.2 \
  -t pgsql11 \
  -host postgres \
  -port 5432 \
  -db "$POSTGRES_DB" \
  -u "$POSTGRES_USER" \
  -p "$POSTGRES_PASSWORD" \
  -s public

echo "ERD generated at $OUTPUT_DIRECTORY/index.html"
