#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verification_dir="$(mktemp -d "${TMPDIR:-/tmp}/adept-monitoring-test.XXXXXX")"
collector_id=""
caddy_id=""
api_mock_id=""
worker_mock_id=""
redaction_id=""
test_network="adept-monitoring-test-$$"
cleanup() {
  # Only remove containers created by this test, never application services.
  if [[ -n "$redaction_id" ]]; then docker rm -f "$redaction_id" >/dev/null; fi
  if [[ -n "$collector_id" ]]; then docker rm -f "$collector_id" >/dev/null; fi
  if [[ -n "$caddy_id" ]]; then docker rm -f "$caddy_id" >/dev/null; fi
  if [[ -n "$worker_mock_id" ]]; then docker rm -f "$worker_mock_id" >/dev/null; fi
  if [[ -n "$api_mock_id" ]]; then docker rm -f "$api_mock_id" >/dev/null; fi
  docker network rm "$test_network" >/dev/null 2>&1 || true
  rm -f \
    "$verification_dir/compose.yaml" \
    "$verification_dir/.env.monitoring" \
    "$verification_dir/log-redaction-input.txt" \
    "$verification_dir/production-log-stages" \
    "$verification_dir/fixture-log-stages"
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
  (.services.alloy.environment.GRAFANA_CLOUD_LOGS_USER == "monitoring-test") and
  (.services.alloy.env_file == null) and
  (.services.alloy.mem_limit == "402653184") and
  (.services.alloy.cpus == 0.5) and
  (.services.alloy.cap_drop == ["ALL"]) and
  (.services.alloy.privileged != true) and
  (.services["engine-worker"].environment.ENGINE_WORKER_THREADS == "2") and
  (.services["engine-worker"].environment.ENGINE_METRICS_BIND_ADDRESS == "0.0.0.0") and
  (.services["engine-worker"].environment.ENGINE_METRICS_PORT == "8001") and
  (.services["engine-worker"].environment.ENGINE_QUEUE_METRICS_INTERVAL_SECONDS == "60") and
  (.services["engine-worker"].expose == ["8001"]) and
  ((.services["engine-worker"].ports // []) | length == 0) and
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

echo "Checking fake secrets are removed by the exact production log stages"
sed -n '/LOG_PIPELINE_STAGES_BEGIN/,/LOG_PIPELINE_STAGES_END/p' \
  "$repository_root/infra/aws/alloy/config.alloy" >"$verification_dir/production-log-stages"
sed -n '/LOG_PIPELINE_STAGES_BEGIN/,/LOG_PIPELINE_STAGES_END/p' \
  "$repository_root/scripts/fixtures/log-redaction.alloy" >"$verification_dir/fixture-log-stages"
cmp "$verification_dir/production-log-stages" "$verification_dir/fixture-log-stages"
cp "$repository_root/scripts/fixtures/log-redaction-input.txt" \
  "$verification_dir/log-redaction-input.txt"
printf 'oversized=FAKE_OVERSIZED_VALUE ' >>"$verification_dir/log-redaction-input.txt"
head -c 17000 /dev/zero | tr '\0' X >>"$verification_dir/log-redaction-input.txt"
printf '\n' >>"$verification_dir/log-redaction-input.txt"
docker run --rm --network none \
  --volume "$repository_root/scripts/fixtures/log-redaction.alloy:/etc/alloy/config.alloy:ro" \
  --volume "$verification_dir:/fixtures:ro" \
  "$alloy_image" validate /etc/alloy/config.alloy
redaction_id="$(docker run -d --network none \
  --volume "$repository_root/scripts/fixtures/log-redaction.alloy:/etc/alloy/config.alloy:ro" \
  --volume "$verification_dir:/fixtures:ro" \
  "$alloy_image" run --disable-reporting --storage.path=/tmp/alloy-redaction \
  /etc/alloy/config.alloy)"
for attempt in {1..30}; do
  redaction_output="$(docker logs "$redaction_id" 2>&1)"
  if grep -q 'safe_event=engine_worker_pool_starting' <<<"$redaction_output"; then break; fi
  sleep 1
done
grep -q 'safe_event=engine_worker_pool_starting' <<<"$redaction_output"
grep -q '\[REDACTED_FIELD\]' <<<"$redaction_output"
grep -q 'labels="{environment=\\"production\\", service=\\"api\\"}"' <<<"$redaction_output"
for marker in \
  FAKE_BEARER_VALUE FAKE_PASSWORD_VALUE private.person@example.test \
  FAKE_ENGINE_ERROR FAKE_SUBJECT FAKE_RULE FAKE_CURSOR FAKE_BOOT_ID \
  FAKE_QUERY_VALUE FAKE_COOKIE_VALUE FAKE_GITHUB_TOKEN \
  123e4567-e89b-12d3-a456-426614174000 FAKE_RAW_BODY FAKE_RAW_HEADER \
  FAKE_OVERSIZED_VALUE FAKE_LABEL_VALUE; do
  if grep -q "$marker" <<<"$redaction_output"; then
    echo "Sensitive log marker escaped redaction: $marker" >&2
    exit 1
  fi
done
docker rm -f "$redaction_id" >/dev/null
redaction_id=""

echo "Checking explicit startup rejects missing credentials"
if docker run --rm --network none "${alloy_mount[@]}" --entrypoint /bin/sh \
  "$alloy_image" /etc/alloy/start.sh validate /etc/alloy/config.alloy >/dev/null 2>&1; then
  echo "Alloy must reject missing credentials" >&2
  exit 1
fi

echo "Checking live host, container and private worker scrapes"
docker network create --internal "$test_network" >/dev/null
caddy_image="$(jq -r '.services.caddy.image' <<<"$enabled")"
api_mock_id="$(docker run -d --network "$test_network" --network-alias api \
  "$caddy_image" caddy respond --listen :8080 --access-log \
  --header 'Content-Type: application/json' --body '{"status":"UP"}')"
worker_metrics_body=$'# TYPE adept_engine_worker_configured_threads gauge\nadept_engine_worker_configured_threads 2\n# TYPE adept_engine_worker_thread_alive gauge\nadept_engine_worker_thread_alive{thread_slot="1"} 1\nadept_engine_worker_thread_alive{thread_slot="2"} 1\n# TYPE adept_engine_worker_queue_ready_jobs gauge\nadept_engine_worker_queue_ready_jobs 0\n# TYPE adept_engine_worker_queue_oldest_ready_wait_seconds gauge\nadept_engine_worker_queue_oldest_ready_wait_seconds 0\n# TYPE adept_engine_worker_queue_collection_success gauge\nadept_engine_worker_queue_collection_success 1\n'
worker_mock_id="$(docker run -d --network "$test_network" --network-alias engine-worker \
  "$caddy_image" caddy respond --listen :8001 \
  --header 'Content-Type: text/plain; version=0.0.4' --body "$worker_metrics_body")"

collector_id="$(docker run -d --network "$test_network" --cgroupns host --cap-drop ALL \
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

worker_metrics="$(docker exec "$collector_id" /bin/bash -ec '
  exec 3<>/dev/tcp/engine-worker/8001
  printf "GET /metrics HTTP/1.0\r\nHost: engine-worker\r\n\r\n" >&3
  while IFS= read -r line <&3; do printf "%s\n" "$line"; done
')"
grep -q '^adept_engine_worker_configured_threads 2' <<<"$worker_metrics"
grep -q '^adept_engine_worker_thread_alive{thread_slot="2"} 1' <<<"$worker_metrics"
grep -q '^adept_engine_worker_queue_ready_jobs 0' <<<"$worker_metrics"

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

echo "Checking the public readiness contract and redacted Caddy access logs"
caddy_id="$(docker run -d --network "$test_network" --env ADEPT_DOMAIN=http://localhost:8080 \
  --env ACME_EMAIL=monitoring-test@example.com \
  --volume "$repository_root/infra/aws/Caddyfile:/etc/caddy/Caddyfile:ro" "$caddy_image")"
for attempt in {1..30}; do
  if docker exec "$caddy_id" curl -fsS http://localhost:2019/config/ >/dev/null 2>&1; then break; fi
  sleep 1
done
status_response="$(docker exec "$caddy_id" curl -fsS http://localhost:8080/api/status)"
test "$status_response" = '{"status":"UP"}'
api_mock_logs="$(docker logs "$api_mock_id" 2>&1)"
jq -R -e 'fromjson? | select(.request.uri? == "/actuator/health/readiness")' \
  <<<"$api_mock_logs" >/dev/null
for path in /actuator/prometheus /actuator/env /metrics; do
  response_code="$(docker exec "$caddy_id" curl -sS -o /dev/null -w '%{http_code}' \
    -H 'X-Forwarded-For: 127.0.0.1' -H 'Forwarded: for=127.0.0.1' "http://localhost:8080$path")"
  test "$response_code" = 404
done
docker exec "$caddy_id" curl -sS -o /dev/null \
  -H 'Authorization: Bearer FAKE_CADDY_AUTH' \
  -H 'Cookie: session=FAKE_CADDY_COOKIE' \
  'http://localhost:8080/metrics?token=FAKE_CADDY_QUERY'
caddy_logs="$(docker logs "$caddy_id" 2>&1)"
if grep -Eq 'FAKE_CADDY_(AUTH|COOKIE|QUERY)' <<<"$caddy_logs"; then
  echo "Caddy exported a fake request secret" >&2
  exit 1
fi
caddy_access_entry="$(jq -R -c 'fromjson? | select((.logger // "") | startswith("http.log.access"))' \
  <<<"$caddy_logs" | tail -n 1)"
jq -e '
  .status == 404 and
  .request.method == "GET" and
  (.request | has("headers") | not) and
  (.request | has("uri") | not) and
  (.request | has("host") | not) and
  (.request | has("remote_ip") | not) and
  (.request | has("client_ip") | not)
' <<<"$caddy_access_entry" >/dev/null
echo "Monitoring verification passed (no production access or Cloud ingestion used)"
