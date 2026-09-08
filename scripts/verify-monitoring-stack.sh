#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verification_dir="$(mktemp -d "${TMPDIR:-/tmp}/adept-monitoring-test.XXXXXX")"
collector_id=""
caddy_id=""
cleanup() {
  # Only remove containers created by this test, never application services.
  if [[ -n "$collector_id" ]]; then docker rm -f "$collector_id" >/dev/null; fi
  if [[ -n "$caddy_id" ]]; then docker rm -f "$caddy_id" >/dev/null; fi
  rm -f "$verification_dir/compose.yaml" "$verification_dir/.env.monitoring"
  rmdir "$verification_dir"
}
trap cleanup EXIT

# Resolve a clean copy: do not read a developer's actual monitoring env file.
cp "$repository_root/infra/aws/compose.yaml" "$verification_dir/compose.yaml"
compose=(docker compose --env-file "$repository_root/infra/aws/.env.production.example"
  --file "$verification_dir/compose.yaml")

echo "Checking monitoring is optional and missing credentials do not break Compose"
baseline="$("${compose[@]}" config --format json)"
jq -e '.services | has("alloy") | not' <<<"$baseline" >/dev/null
"${compose[@]}" --profile monitoring config --quiet
cp "$repository_root/scripts/fixtures/monitoring.env.example" "$verification_dir/.env.monitoring"
enabled="$("${compose[@]}" --profile monitoring config --format json)"
jq -e --argjson baseline "$baseline" '
  (.services | del(.alloy)) == $baseline.services and
  ([.services | to_entries[] | select((.value.ports // [] | length) > 0) | .key] == ["caddy"]) and
  (.services.alloy.environment.GRAFANA_CLOUD_TOKEN == "monitoring-test") and
  (.services.alloy.env_file == null) and
  (.services.alloy.mem_limit == "402653184") and
  (.services.alloy.cpus == 0.5) and
  (.services.alloy.cap_drop == ["ALL"]) and
  (.services.alloy.privileged != true) and
  (.services.alloy.image | test("@sha256:[0-9a-f]{64}$"))
' <<<"$enabled" >/dev/null

alloy_image="$(jq -r '.services.alloy.image' <<<"$enabled")"
alloy_mount=(--volume "$repository_root/infra/aws/alloy:/etc/alloy:ro")
test_env=(--env-file "$repository_root/scripts/fixtures/monitoring.env.example")

echo "Validating the pinned Alloy configuration offline"
docker run --rm --network none "${alloy_mount[@]}" "${test_env[@]}" \
  "$alloy_image" validate /etc/alloy/config.alloy
docker run --rm --network none "${alloy_mount[@]}" "$alloy_image" \
  fmt --test /etc/alloy/config.alloy

echo "Checking explicit startup rejects missing credentials"
if docker run --rm --network none "${alloy_mount[@]}" --entrypoint /bin/sh \
  "$alloy_image" /etc/alloy/start.sh validate /etc/alloy/config.alloy >/dev/null 2>&1; then
  echo "Alloy must reject missing credentials" >&2
  exit 1
fi

echo "Checking live host/container samples and the image's real health check"
collector_id="$(docker run -d --network none --cgroupns host --cap-drop ALL \
  --security-opt no-new-privileges --memory 384m --memory-swap 384m --cpus 0.5 --pids-limit 128 \
  --label com.docker.compose.project=adept-production \
  --label com.docker.compose.service=monitoring-test \
  --label monitoring.secret=must-not-be-exported \
  "${alloy_mount[@]}" "${test_env[@]}" \
  --volume /:/rootfs:ro --volume /sys:/sys:ro \
  --volume /var/lib/docker:/var/lib/docker:ro \
  --volume /var/run/docker.sock:/var/run/docker.sock:ro \
  --entrypoint /bin/sh "$alloy_image" /etc/alloy/start.sh \
  run --disable-reporting --storage.path=/tmp/alloy-data /etc/alloy/config.alloy)"

for attempt in {1..30}; do
  if docker exec "$collector_id" /bin/bash /etc/alloy/healthcheck.sh >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$collector_id" /bin/bash /etc/alloy/healthcheck.sh

scrape_component() {
  docker exec "$collector_id" /bin/bash -ec '
    exec 3<>/dev/tcp/127.0.0.1/12345
    printf "GET /api/v0/component/%s/metrics HTTP/1.0\r\nHost: localhost\r\n\r\n" "$1" >&3
    while IFS= read -r line <&3; do printf "%s\n" "$line"; done
  ' -- "$1"
}

host_metrics="$(scrape_component prometheus.exporter.unix.host)"
grep -q '^node_memory_MemTotal_bytes ' <<<"$host_metrics"
grep -q '^node_cpu_seconds_total{' <<<"$host_metrics"
grep -q '^node_filesystem_size_bytes{' <<<"$host_metrics"
# It must report the host RAM, not the collector's 384 MiB limit.
awk '/^node_memory_MemTotal_bytes / { if ($2 > 402653184) ok=1 } END { exit !ok }' <<<"$host_metrics"

for attempt in {1..30}; do
  container_metrics="$(scrape_component prometheus.exporter.cadvisor.containers)"
  if grep -q 'container_memory_working_set_bytes{.*container_label_com_docker_compose_service="monitoring-test"' <<<"$container_metrics"; then break; fi
  sleep 1
done
grep -q 'container_memory_working_set_bytes{.*container_label_com_docker_compose_service="monitoring-test"' <<<"$container_metrics"
if grep -q 'must-not-be-exported' <<<"$container_metrics"; then
  echo "Unexpected container metadata export" >&2
  exit 1
fi

echo "Checking public operational paths stay blocked even with spoofed forwarding headers"
caddy_image="$(jq -r '.services.caddy.image' <<<"$enabled")"
caddy_id="$(docker run -d --network none --env ADEPT_DOMAIN=http://localhost:8080 \
  --env ACME_EMAIL=monitoring-test@example.com \
  --volume "$repository_root/infra/aws/Caddyfile:/etc/caddy/Caddyfile:ro" "$caddy_image")"
for attempt in {1..30}; do
  if docker exec "$caddy_id" curl -fsS http://localhost:2019/config/ >/dev/null 2>&1; then break; fi
  sleep 1
done
for path in /actuator/prometheus /actuator/env /metrics; do
  response_code="$(docker exec "$caddy_id" curl -sS -o /dev/null -w '%{http_code}' \
    -H 'X-Forwarded-For: 127.0.0.1' -H 'Forwarded: for=127.0.0.1' "http://localhost:8080$path")"
  test "$response_code" = 404
done
echo "Monitoring verification passed (no production access or Cloud ingestion used)"
