#!/bin/sh
set -eu

# Profile-disabled application deployments need no monitoring file. When the
# collector is explicitly started, reject missing values without printing them.
test -n "${GRAFANA_CLOUD_METRICS_USER:-}" || { echo "Set GRAFANA_CLOUD_METRICS_USER in .env.monitoring" >&2; exit 1; }
test -n "${GRAFANA_CLOUD_TOKEN:-}" || { echo "Set GRAFANA_CLOUD_TOKEN in .env.monitoring" >&2; exit 1; }
case "${GRAFANA_CLOUD_METRICS_URL:-}" in
  https://*) ;;
  *) echo "Set an HTTPS GRAFANA_CLOUD_METRICS_URL in .env.monitoring" >&2; exit 1 ;;
esac

exec /usr/bin/alloy "$@"
