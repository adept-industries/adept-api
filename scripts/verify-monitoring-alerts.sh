#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verification_dir="$(mktemp -d "${TMPDIR:-/tmp}/adept-alert-test.XXXXXX")"
trap 'rm -rf "$verification_dir"' EXIT
# Test tooling only. No Prometheus server is installed on production.
prometheus_image="prom/prometheus:v3.5.0@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996"

# The same source feeds Terraform and these tests; do not duplicate alert queries.
jq 'with_entries(.value.expr |= (
  gsub("__ENV__"; "production") |
  gsub("__WEBSITE_PROBE_JOB__"; "adept-website") |
  gsub("__API_PROBE_JOB__"; "adept-api-readiness")
))' "$repository_root/infra/aws/grafana/alert-definitions.json" >"$verification_dir/definitions.json"

jq '{groups: [{name: "adept-tests", interval: "1m", rules: [
  to_entries[] | {alert: .key, expr: ("(" + .value.expr + ") > 0"), for: .value.for}
]}]}' "$verification_dir/definitions.json" >"$verification_dir/rules.json"

jq --slurpfile definitions "$verification_dir/definitions.json" '
  .baseline as $baseline |
  {
    rule_files: ["rules.json"],
    evaluation_interval: "1m",
    tests: [.cases[] |
      . as $case |
      {
        name: .name,
        interval: "1m",
        input_series: (
          ($baseline | map(select((.series | test($case.omit // "^$")) | not))) as $base |
          [($base + ($case.overrides // [])) | group_by(.series)[] | last]
        ),
        promql_expr_test: [
          .expected | to_entries[] |
          {expr: $definitions[0][.key].expr,
           eval_time: ($case.eval_time // "20m"),
           exp_samples: [{labels: "{}", value: .value}]}
        ],
        alert_rule_test: (
          [.expected | to_entries[] |
            .key as $name |
            {eval_time: ($case.eval_time // "20m"), alertname: $name,
             exp_alerts: (if (if (($case.alerts // {}) | has($name)) then $case.alerts[$name] else (.value == 1) end)
                          then [{exp_labels: {}, exp_annotations: {}}] else [] end)}
          ] +
          [(.extra_alert_checks // [])[] |
            {eval_time: .at, alertname: .alert,
             exp_alerts: (if .firing then [{exp_labels: {}, exp_annotations: {}}] else [] end)}
          ]
        )
      }
    ]
  }
' "$repository_root/scripts/fixtures/monitoring-alert-cases.json" >"$verification_dir/tests.json"

# Parse every PromQL dashboard target, including variables, with the same engine.
jq -s '{
  groups: [{name: "dashboard-query-syntax", rules: [
    .[] | .panels[] | select(.type != "logs") | .targets[] |
    {expr: (.expr | gsub("\\$environment"; "production") | gsub("\\$__range"; "1h"))}
  ] | to_entries | map(.value + {record: ("dashboard_test_" + (.key | tostring))})}]
}' "$repository_root"/infra/aws/grafana/dashboards/*.json >"$verification_dir/dashboards.json"

docker run --rm --network none --volume "$verification_dir:/tests:ro" --workdir /tests \
  --entrypoint /bin/promtool "$prometheus_image" check rules rules.json dashboards.json
docker run --rm --network none --volume "$verification_dir:/tests:ro" --workdir /tests \
  --entrypoint /bin/promtool "$prometheus_image" test rules tests.json

echo "Alert query, pending/firing/recovery, missing telemetry and dashboard syntax checks passed"
