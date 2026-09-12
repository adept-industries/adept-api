# Adept monitoring: owner rollout and rollback

Only the production owner performs this rollout. The teammate delivers code and
local tests; no production access is needed to finish the PR.

**Do not change .env.production.** Create /opt/adept/.env.monitoring separately.
Merging this PR deploys application images, but does not install Compose, Caddy
or Alloy configuration. Creating the monitoring env file alone is not enough.

## 1. What runs where

- Existing VM: Grafana Alloy collects metrics and selected operational logs.
- Grafana Cloud: Prometheus-compatible storage, Loki logs, dashboards and alerts.
- External Grafana Synthetic Monitoring: website and API availability checks.
- No separate Prometheus, Grafana or Loki server is installed on the VM.
- One worker container still has two processing threads (one is also supported).
- Only Caddy publishes ports 80 and 443; port 8001 is private.

| Metric job | Source | Interval |
|---|---|---|
| node-exporter | Host CPU, RAM, swap and root filesystem | 60 seconds |
| cadvisor | Selected Compose container resources, last-seen and start time | 60 seconds |
| adept-api | http://api:8080/actuator/prometheus | 60 seconds |
| adept-engine-worker | http://engine-worker:8001/metrics | 60 seconds |
| alloy | Collector process and delivery metrics | 60 seconds |

Worker reachability is not job-processing health. Watch configured/live threads,
successful polls, active-job duration, queue snapshot freshness and outcomes.
Container last-seen/start-time metrics are proxies, not Docker health-check
results or exact restart counts. Fast restarts between scrapes can be missed.

### Privacy and resource boundaries

Alloy exports only reconstructed operational summaries: known worker event names,
warning/error severity and HTTP 4xx/5xx status. Unknown messages, stack traces,
request bodies, URLs, headers, customer IDs and arbitrary exception fields are
never forwarded. Unselected lines are dropped; this deliberately loses detailed
diagnostics. Consult local application logs with owner access when needed.
Only environment and service are Loki labels. Normal access requests and
per-job success messages are not shipped.

Caddy removes request URI, client addresses, headers and user data from its
access logs before Docker captures them. Other local application logs may still
contain sensitive details; do not paste raw logs into tickets or chat.

Only Alloy receives the Grafana credentials through its service env_file.
However, the read-only host-root and Docker-socket mounts are privileged access:
Alloy can read host files (including secrets) and the socket is root-equivalent.
A :ro socket mount does not make Docker API operations read-only. This is a
trusted, digest-pinned collector, not a security sandbox. Its UI is loopback-only.

Alloy is capped at 384 MiB RAM, no extra swap, 0.5 CPU and 128 processes.
Loki uses bounded batches/retries, at most 16 streams and a 20-lines/second
per-service limit (burst 100). Docker rotates logs at 10 MiB × 5 files.
The alloy_data volume preserves metrics WAL and Docker log positions across
restarts. WAL retention is approximately one hour plus segment/truncation
overhead, **not a hard disk-size limit**. Prolonged outages, rate limits or disk
pressure can lose telemetry. Watch volume growth and Grafana ingestion usage.

## 2. Prerequisites and local verification

- PR2's worker metrics image is deployed before worker scraping is enabled.
- Docker Compose 2.24+ supports optional env_file entries.
- Linux host paths: /proc, /sys, /var/lib/docker and /var/run/docker.sock.
- Check the host containerd socket. The committed Alloy path assumes
  /var/run/docker/containerd/containerd.sock; if the host uses
  /run/containerd/containerd.sock, change only the staged containerd_host value
  to /rootfs/run/containerd/containerd.sock before validation.
- Approve a Grafana Cloud stack and its plan first. Check metrics, logs and
  Synthetic Monitoring allowances; do not enable paid upgrades without approval.
- Stack supports Grafana's direct per-rule contact-point routing (10.4+).
- Terraform 1.7+ for mocked tests; CI uses 1.16.1 and the committed provider lock.

Run locally from adept-api (Docker and jq required):

```bash
bash scripts/verify-production-stack.sh
bash scripts/verify-monitoring-stack.sh
bash scripts/verify-monitoring-alerts.sh
./mvnw -B clean verify
terraform -chdir=infra/aws/grafana/terraform init -backend=false -input=false
terraform -chdir=infra/aws/grafana/terraform validate
terraform -chdir=infra/aws/grafana/terraform test
```

These checks use dummy credentials/disposable containers and a mocked Grafana
provider. They test real Alloy discovery/sanitization, Caddy routing, PromQL
healthy/pending/firing/recovery behavior and provisioning structure. They do
**not** prove Grafana Cloud ingestion, real email delivery or production health.
The owner must verify those below.

## 3. Install reviewed files on AWS

Use a reviewed checkout; coordinate with teammates so no deployments run while
replacing configuration. Do not upload the application .env.production example.

| Repository file | VM destination |
|---|---|
| infra/aws/compose.yaml | /opt/adept/compose.yaml |
| infra/aws/Caddyfile | /opt/adept/Caddyfile |
| infra/aws/alloy/config.alloy | /opt/adept/alloy/config.alloy |
| infra/aws/alloy/start.sh | /opt/adept/alloy/start.sh |
| infra/aws/alloy/healthcheck.sh | /opt/adept/alloy/healthcheck.sh |
| infra/aws/.env.monitoring.example | Stage as a template; never overwrite an existing real file |

From the owner's local checkout:

```bash
ssh -i ~/.ssh/adept-staging ubuntu@3.111.250.16 "install -d -m 700 /tmp/adept-monitoring-rollout /tmp/adept-monitoring-rollout/alloy"
scp -i ~/.ssh/adept-staging infra/aws/compose.yaml infra/aws/Caddyfile infra/aws/.env.monitoring.example ubuntu@3.111.250.16:/tmp/adept-monitoring-rollout/
scp -i ~/.ssh/adept-staging infra/aws/alloy/config.alloy infra/aws/alloy/start.sh infra/aws/alloy/healthcheck.sh ubuntu@3.111.250.16:/tmp/adept-monitoring-rollout/alloy/
ssh -i ~/.ssh/adept-staging ubuntu@3.111.250.16
```

On the VM, back up configuration and record the printed backup directory:

```bash
monitoring_backup_dir=$(sudo mktemp -d /opt/adept/monitoring-backup.XXXXXX)
sudo cp -a /opt/adept/compose.yaml /opt/adept/Caddyfile "$monitoring_backup_dir/"
if sudo test -d /opt/adept/alloy; then sudo cp -a /opt/adept/alloy "$monitoring_backup_dir/"; fi
if sudo test -f /opt/adept/.env.monitoring; then sudo cp -a /opt/adept/.env.monitoring "$monitoring_backup_dir/"; fi
printf 'Backup: %s\n' "$monitoring_backup_dir"
sudo diff -u /opt/adept/compose.yaml /tmp/adept-monitoring-rollout/compose.yaml || true
sudo diff -u /opt/adept/Caddyfile /tmp/adept-monitoring-rollout/Caddyfile || true
```

Review differences before continuing. Preserve any intentional VM-only settings,
existing image-variable names and all application volume names. Do not print
expanded Compose configuration or diff secret env files.

Install the reviewed files:

```bash
sudo install -d -m 755 /opt/adept/alloy
sudo install -m 644 /tmp/adept-monitoring-rollout/compose.yaml /opt/adept/compose.yaml
sudo install -m 644 /tmp/adept-monitoring-rollout/Caddyfile /opt/adept/Caddyfile
sudo install -m 644 /tmp/adept-monitoring-rollout/alloy/config.alloy /opt/adept/alloy/config.alloy
sudo install -m 755 /tmp/adept-monitoring-rollout/alloy/start.sh /opt/adept/alloy/start.sh
sudo install -m 755 /tmp/adept-monitoring-rollout/alloy/healthcheck.sh /opt/adept/alloy/healthcheck.sh
```

Create the separate credentials file only if absent:

```bash
if ! sudo test -e /opt/adept/.env.monitoring; then
  sudo install -m 600 /tmp/adept-monitoring-rollout/.env.monitoring.example /opt/adept/.env.monitoring
fi
sudo nano /opt/adept/.env.monitoring
sudo chmod 600 /opt/adept/.env.monitoring
```

Fill all five values from the same approved Grafana Cloud stack:

- GRAFANA_CLOUD_METRICS_URL: Prometheus remote-write HTTPS endpoint.
- GRAFANA_CLOUD_METRICS_USER: Prometheus instance ID, not a login email.
- GRAFANA_CLOUD_LOGS_URL: Loki push HTTPS endpoint.
- GRAFANA_CLOUD_LOGS_USER: Loki instance ID.
- GRAFANA_CLOUD_TOKEN: stack-scoped Access Policy token with only metrics:write
  and logs:write. This is not the Terraform service-account token.

No new values are required in .env.production: worker threads default to 2 and
queue metrics refresh defaults to 60 seconds. Never copy GitHub secrets over
the VM's existing application secrets.

## 4. Validate and activate

Define this helper in the SSH shell:

```bash
dc() {
  sudo docker compose --env-file /opt/adept/.env.production -f /opt/adept/compose.yaml "$@"
}
dc --profile monitoring config --quiet
dc run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
dc --profile monitoring run --rm --no-deps alloy validate /etc/alloy/config.alloy
```

One-off validation containers mount the newly installed files; do not validate
only through an old running bind mount. If validation fails, restore the saved
configuration before continuing. A failed Alloy startup must not stop the app.

Apply during a short maintenance window:

```bash
dc up -d --no-deps --force-recreate caddy
dc up -d --no-deps --force-recreate engine-worker
dc --profile monitoring up -d --no-deps --force-recreate alloy
dc --profile monitoring ps
dc exec -T alloy /bin/bash /etc/alloy/healthcheck.sh
dc exec -T caddy curl -fsS -o /dev/null http://api:8080/actuator/prometheus
dc exec -T caddy curl -fsS -o /dev/null http://engine-worker:8001/metrics
```

Recreating Caddy ensures replaced file bind mounts use the reviewed configuration;
expect a brief public interruption. TLS data persists. The worker drains active
jobs within its existing 120-second grace period; stale-lease recovery remains
unchanged. Do not recreate PostgreSQL, API or frontend for this installation.

Alloy /-/ready proves collector startup only. Verify that fresh metrics and
sanitized log entries actually arrive in Cloud after several scrape intervals.

## 5. Configure Cloud dashboards, probes and notifications

Creating .env.monitoring does not provision Cloud dashboards/alerts. Run these
steps locally as owner, not on AWS. They do not use Adept SMTP or its alert queue.

In infra/aws/grafana/terraform, copy terraform.tfvars.example to the ignored
terraform.tfvars. Set the Grafana stack URL, Prometheus datasource UID,
notification email addresses and environment. Keep alerts_paused = true.

Supply a separate stack-scoped service-account token with dashboard/alert
provisioning permissions. In a Bash shell, avoid putting it in shell history:

```bash
read -rsp "Grafana service-account token: " TF_VAR_grafana_auth; printf '\n'
export TF_VAR_grafana_auth
terraform init
terraform plan
terraform apply
```

Review the plan: only the Adept folder, three dashboards, message template,
contact point and rule group should be managed. Direct per-rule routing sends
Adept alerts to "Adept owner email" without replacing any global notification
policy tree. Recovery emails are enabled. Notifications wait one minute,
group by folder/rule/environment/service/severity, update every ten minutes and
repeat every four hours.

Terraform state/plan files may contain personal data or sensitive settings;
keep them private and backed up outside Git. Never commit credentials or state.
Dashboards have Prometheus/Loki datasource dropdowns: select the corresponding
stack sources and production environment. Manual dashboard JSON import is also
possible, but **does not install alert rules or contact points**; use Terraform
for those or manually reproduce and preview every definition.

### External availability checks (both setup methods need this)

In Grafana Synthetic Monitoring, create HTTP checks using approved public probes
outside the VM. Review estimated usage before enabling them.

| Check name / exported job | URL | Required assertions |
|---|---|---|
| adept-website | https://adeptindustries.dev/ | HTTP 200 and body contains "Adept" |
| adept-api-readiness | https://adeptindustries.dev/api/status | HTTP 200 and JSON status exactly UP |

Use a 60-second interval if it fits the approved allowance. Add environment =
production to both checks. The check name supplies the job label; confirm actual
labels in Explore rather than adding a conflicting reserved job label.
For an HTTP body regex, use `"status"\s*:\s*"UP"` and require JSON content type.
Do not use /api/v1/health, an arbitrary health URL or HTTP 200 alone: the public
contract added by this PR is exactly /api/status, backed by Spring readiness
(including DB availability). /actuator* and /metrics* remain blocked.

Preview probe_success for both named jobs before enabling operational alerts.
A website HTML check verifies serving the page, not successful JavaScript
execution or sign-in. A browser journey can be added later if needed.

### Test firing and recovery without stopping production

Keep operational alerts paused while testing:

```bash
terraform apply -var="notification_test_enabled=true" -var="notification_test_firing=true"
# Wait for the firing email and inspect Grafana notification history.
terraform apply -var="notification_test_enabled=true" -var="notification_test_firing=false"
# Wait for the recovery email (up to the ten-minute group interval).
terraform apply -var="notification_test_enabled=false"
```

Only after queries, probes and notifications work, set alerts_paused = false,
review the plan and apply again. Then unset TF_VAR_grafana_auth.

## 6. What the alerts mean

Exact definitions live in infra/aws/grafana/alert-definitions.json and are shared
by Terraform and local Prometheus tests. Every healthy query returns 0; an
unhealthy one returns 1. Missing required series explicitly produces 1 where
possible; unexpected No Data and query errors are Alerting, never normal
recovery. A stopped collector may take the datasource lookback (usually five
minutes) plus the rule's pending period to alert. Alerting lives outside the VM.

| Condition | Initial threshold and pending period |
|---|---|
| Website/API probes | Failing or absent for 3 minutes |
| Collector, API, worker, host, container scrapes | Failed/absent for 5 minutes |
| Worker threads | Missing/dead slots or missing thread telemetry for 5 minutes |
| Idle polling | Poll age >5 minutes while idle, sustained another 5 minutes |
| Long job | Active duration >30 minutes, sustained 5 minutes |
| Queue snapshot | Collection failed or last success >3 minutes old, sustained 5 minutes |
| Queue delay/backlog | Oldest ready wait >10 minutes or >100 ready jobs, sustained 10 minutes |
| New dead letters | New durable transition within 15 minutes, sustained 1 minute |
| Repeated failures | At least 5 failed attempts in 15 minutes, sustained 3 minutes |
| API errors | >5% 5xx over 5 minutes, sustained 5 minutes |
| CPU/RAM/disk | >85% CPU, <10% available RAM or <15% disk available for 10 minutes |
| Swap | >80% occupied for 15 minutes (not proof of active swapping) |

Healthy idle queues and future schedules do not trigger backlog alerts.
Busy-scope deferrals and continuation pages are not failed attempts. A long
active job does not count as stalled idle polling. Cached queue values can
remain after collection failure; always check the snapshot-health alert too.
Counters reset on worker restart; their initialized zeros help detect a first
event, but events before a first scrape or just before restart may be missed.
Tune thresholds from real beta usage, not by hiding missing-data failures.

### Verify actual data and overhead

Explore should show fresh up samples for node-exporter, cadvisor, adept-api,
adept-engine-worker and alloy. Check these metrics:

```promql
adept_engine_worker_configured_threads{environment="production"}
adept_engine_worker_thread_alive{environment="production"}
adept_engine_worker_queue_ready_jobs{environment="production"}
adept_engine_worker_queue_collection_success{environment="production"}
time() - adept_engine_worker_queue_last_success_timestamp_seconds{environment="production"}
node_memory_MemAvailable_bytes{job="node-exporter",environment="production"}
```

For Loki, query `{environment="production",service="engine-worker"}` or service =
api, engine-api, caddy. Quiet services may legitimately have no selected logs.
Alloy itself is not exported through the Docker log pipeline; inspect its local
logs privately and use remote-write/drop metrics for delivery diagnostics.

On the VM:

```bash
dc --profile monitoring stats --no-stream
df -h / /var/lib/docker
sudo docker system df -v
dc logs --tail=50 alloy
```

Check Grafana usage, WAL/disk growth and collector CPU/RAM after rollout and
during a busy sync. Do not assume monitoring is complete just because Alloy is
running. Record successful ingestion, both worker slots, public probe assertions,
alert email and recovery before declaring final acceptance.

## 7. Rollback and token rotation

To stop only monitoring: `dc --profile monitoring stop alloy`. Application data
and alloy_data remain. If metrics are intentionally disabled for a long period,
pause the Grafana rules explicitly; otherwise missing-telemetry alerts are expected.

For a full config rollback, stop Alloy, restore compose.yaml, Caddyfile and the
alloy directory from the recorded backup, validate, and recreate only Caddy and
engine-worker. Recreate Alloy only if reverting to a previously working collector.
If Caddy config is restored by replacing a file, do not rely on an old bind-mount
reload. Preserve .env.production, image tags and all application volumes.
Never run docker compose down -v. Do not destroy Terraform resources to
silence an outage.

For ingestion-token rotation, edit only .env.monitoring, force-recreate Alloy,
verify fresh Cloud samples, then revoke the old token. Keep the previous token
valid until verification. No application restart or application env edit is needed.

## References

- [PromQL comparison semantics](https://prometheus.io/docs/prometheus/latest/querying/operators/)
- [Grafana No Data and error behavior](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/)
- [Alloy log processing](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.process/)
- [Grafana notification setup](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/)
