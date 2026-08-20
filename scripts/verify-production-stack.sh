#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repository_root/infra/aws/compose.yaml"
environment_file="$repository_root/infra/aws/.env.production.example"
caddy_file="$repository_root/infra/aws/Caddyfile"

for command_name in docker jq; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command is unavailable: $command_name" >&2
    exit 1
  fi
done

echo "==> Validating production Compose syntax"
docker compose \
  --env-file "$environment_file" \
  --file "$compose_file" \
  config --quiet

resolved_config="$(
  docker compose \
    --env-file "$environment_file" \
    --file "$compose_file" \
    config --format json
)"

echo "==> Verifying immutable application image tags"
jq -e '
  .services.api.image
    | test("^ghcr\\.io/adept-industries/adept-api:sha-[0-9a-f]{40}$")
' <<<"$resolved_config" >/dev/null
jq -e '
  .services["engine-api"].image
    | test("^ghcr\\.io/adept-industries/adept-engine:sha-[0-9a-f]{40}$")
' <<<"$resolved_config" >/dev/null
jq -e '
  .services.frontend.image
    | test("^ghcr\\.io/adept-industries/adept-frontend:sha-[0-9a-f]{40}$")
' <<<"$resolved_config" >/dev/null

echo "==> Verifying that only Caddy publishes host ports"
published_services="$(
  jq -r '
    .services
    | to_entries[]
    | select(((.value.ports // []) | length) > 0)
    | .key
  ' <<<"$resolved_config"
)"
if [[ "$published_services" != "caddy" ]]; then
  echo "Only Caddy may publish host ports; found: $published_services" >&2
  exit 1
fi

published_caddy_ports="$(
  jq -r '
    .services.caddy.ports[].published
  ' <<<"$resolved_config" | sort -n | paste -sd, -
)"
if [[ "$published_caddy_ports" != "80,443" ]]; then
  echo "Caddy must publish only ports 80 and 443; found: $published_caddy_ports" >&2
  exit 1
fi

echo "==> Verifying engine worker provider credential wiring"
jq -e '
  [
    "GITHUB_APP_ID",
    "GITHUB_APP_PRIVATE_KEY_BASE64",
    "JIRA_CLIENT_ID",
    "JIRA_CLIENT_SECRET",
    "APP_INTEGRATION_ENCRYPTION_ACTIVE_KEY_VERSION",
    "APP_INTEGRATION_ENCRYPTION_KEY_V1_BASE64",
    "ENGINE_JOB_LOCK_TIMEOUT_SECONDS"
  ] - (.services["engine-worker"].environment | keys)
  | length == 0
' <<<"$resolved_config" >/dev/null

echo "==> Validating Caddy configuration"
caddy_image="$(jq -r '.services.caddy.image' <<<"$resolved_config")"
docker run --rm \
  --volume "$caddy_file:/etc/caddy/Caddyfile:ro" \
  --env ADEPT_DOMAIN=adeptindustries.dev \
  --env ACME_EMAIL=deployment-test@example.com \
  "$caddy_image" \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

echo "==> Production stack verification passed"
