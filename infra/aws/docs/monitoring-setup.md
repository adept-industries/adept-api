# Monitoring PR 1 — setup and rollback

**Owner only:** create `/opt/adept/.env.monitoring` and install the reviewed files.
Do not change `.env.production` or share production access with the teammate.
Merging this PR deploys the API image, but does **not** install Compose, Caddy,
or Alloy configuration on AWS.

## What this adds

| Source | Measurements |
|---|---|
| Linux host | CPU, available memory, swap, load, filesystem space and disk I/O |
| Docker containers | CPU, memory, network, disk I/O, last-seen and start time |
| API | Request counts/errors/latency buckets, JVM and connection-pool usage |
| Alloy itself | Collector resources and remote-write queue/failures |

Alloy sends Prometheus-compatible metrics to Grafana Cloud every 60 seconds.
There is no Grafana, Prometheus database, or Loki server on the VM.
Worker metrics come in PR 2; logs, dashboards and alert rules come in PR 3.
Container last-seen/start-time metrics are **not** Docker health-check status
or an exact restart counter. This PR does not claim to export those.

## Requirements and permissions

- Linux Docker host with Docker Compose **2.24.0 or newer** (`env_file.required`).
- Standard Docker paths: `/var/run/docker.sock`, `/var/lib/docker`, and `/sys`.
- With Docker's overlayfs/containerd image store, cAdvisor also needs Docker's
  managed socket at `/var/run/docker/containerd/containerd.sock`, reached through
  the existing host-root mount. A different containerd setup needs its actual
  socket configured before rollout; do not accept missing container samples.
- Owner-approved Grafana Cloud stack and write-only token.

Only Caddy publishes ports 80/443. The API metrics endpoint allows internal,
read-only scrapes; Caddy explicitly rejects public operational paths. Alloy's
own HTTP listener binds to container loopback, not a published port.

Alloy drops Linux capabilities and uses no-new-privileges, but **it is still a
trusted host agent, not a sandbox**. Read-only host mounts can expose host files,
including secrets; socket access can control Docker regardless of `:ro`.
The config does not load or export `.env.production`, environment values, or
arbitrary container labels. Nevertheless, the mounts are a privilege trade-off
the owner must accept. Do not add privileged mode, open ports, or remote config.
If this trust is unacceptable, stop and design restricted exporters/socket
access separately.

## 1. Get the Grafana details

In your Grafana Cloud stack, copy the Prometheus remote-write URL and instance ID.
Create a stack-scoped access-policy token with **metrics:write** only.
Use the instance ID as the username, not your email. PR 3 will add logs:write
and the Loki URL/instance ID.

Check the stack's Usage & Billing page and its current limits. No paid upgrade
or billing guarantee is part of this PR.

## 2. Stage reviewed files

From your local `adept-api` checkout, using the existing SSH key:

```bash
ssh -i ~/.ssh/adept-staging ubuntu@3.111.250.16 'mkdir -p /tmp/adept-monitoring-rollout/alloy'
scp -i ~/.ssh/adept-staging infra/aws/compose.yaml infra/aws/Caddyfile infra/aws/.env.monitoring.example ubuntu@3.111.250.16:/tmp/adept-monitoring-rollout/
scp -i ~/.ssh/adept-staging infra/aws/alloy/config.alloy infra/aws/alloy/start.sh infra/aws/alloy/healthcheck.sh ubuntu@3.111.250.16:/tmp/adept-monitoring-rollout/alloy/
```

Upload only these non-secret files. Never upload an example over the real
`.env.production`.

| Repository file | Installed VM file |
|---|---|
| `infra/aws/compose.yaml` | `/opt/adept/compose.yaml` |
| `infra/aws/Caddyfile` | `/opt/adept/Caddyfile` |
| `infra/aws/alloy/config.alloy` | `/opt/adept/alloy/config.alloy` |
| `infra/aws/alloy/start.sh` | `/opt/adept/alloy/start.sh` |
| `infra/aws/alloy/healthcheck.sh` | `/opt/adept/alloy/healthcheck.sh` |
| `infra/aws/.env.monitoring.example` | Template for owner-created `/opt/adept/.env.monitoring` |

## 3. Back up and install on AWS

Use one SSH session. Confirm no application deployment is currently running.
Review the staged Compose diff against the live file and preserve any unrelated
VM customizations. Then:

```bash
monitoring_backup_dir=$(sudo mktemp -d /opt/adept/monitoring-backup.XXXXXX)
sudo cp -a /opt/adept/compose.yaml /opt/adept/Caddyfile "$monitoring_backup_dir/"
if [ -d /opt/adept/alloy ]; then sudo cp -a /opt/adept/alloy "$monitoring_backup_dir/"; fi
printf 'Save this rollback path: %s\n' "$monitoring_backup_dir"

sudo install -d -m 755 /opt/adept/alloy
sudo install -m 644 /tmp/adept-monitoring-rollout/compose.yaml /opt/adept/compose.yaml
sudo cp /tmp/adept-monitoring-rollout/Caddyfile /opt/adept/Caddyfile
sudo install -m 644 /tmp/adept-monitoring-rollout/alloy/config.alloy /opt/adept/alloy/config.alloy
sudo install -m 644 /tmp/adept-monitoring-rollout/alloy/start.sh /opt/adept/alloy/start.sh
sudo install -m 644 /tmp/adept-monitoring-rollout/alloy/healthcheck.sh /opt/adept/alloy/healthcheck.sh
```

Create the monitoring file **only if it does not already exist**:

```bash
if ! sudo test -e /opt/adept/.env.monitoring; then
  sudo install -m 600 /tmp/adept-monitoring-rollout/.env.monitoring.example /opt/adept/.env.monitoring
fi
sudo nano /opt/adept/.env.monitoring
sudo chmod 600 /opt/adept/.env.monitoring
```

Fill in `GRAFANA_CLOUD_METRICS_URL`, `GRAFANA_CLOUD_METRICS_USER`, and
`GRAFANA_CLOUD_TOKEN`. Leave the two LOGS values blank for PR 1.
Do not paste real values into GitHub, chat, shell commands, or screenshots.
The file is supplied only to Alloy, not to the application containers.

## 4. Validate and start

Define this helper in that SSH session. It always includes the existing
application env file needed for Compose interpolation:

```bash
dc() {
  sudo docker compose --env-file /opt/adept/.env.production -f /opt/adept/compose.yaml "$@"
}
dc --profile monitoring config --quiet
dc exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
dc --profile monitoring run --rm --no-deps alloy validate /etc/alloy/config.alloy
```

Use `config --quiet`, **not plain `config`**, which can print expanded secrets.
Alloy validation checks syntax, not whether Grafana accepts the token.

After the new API image has deployed successfully:

```bash
dc exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
dc --profile monitoring up -d --no-deps --force-recreate alloy
dc --profile monitoring ps
dc exec -T alloy /bin/bash /etc/alloy/healthcheck.sh
dc exec -T caddy curl -fsS -o /dev/null http://api:8080/actuator/prometheus
```

No database/worker restart is needed. Keep app deployment workflows unchanged.
Only Alloy starts; Caddy reloads its private-path restriction without replacing
the running Caddy container. The plain Caddyfile copy above preserves the inode
used by its single-file bind mount; replacing that file atomically would require
recreating Caddy instead. Future monitoring config updates need this manual
file-install process too, followed by Alloy recreation.

## 5. Confirm real data and resource usage

In Grafana Explore, verify fresh samples after a few scrape intervals:

- `up{job="adept-api"}` is 1.
- `node_memory_MemTotal_bytes{job="node-exporter"}` agrees with the VM's RAM
  from `free -b`, not Alloy's 384 MiB limit.
- `node_filesystem_avail_bytes{job="node-exporter",mountpoint="/"}` reflects
  free space on the VM's root filesystem.
- `container_memory_working_set_bytes{job="cadvisor"}` has a `service` label
  for the running Adept services, not only the collector/root cgroup.
- `http_server_requests_seconds_bucket{job="adept-api"}`,
  `jvm_memory_used_bytes{job="adept-api"}`, and `hikaricp_connections{job="adept-api"}`
  are present. Exercise an API route to generate request metrics.

From outside the VM, `https://adeptindustries.dev/actuator/prometheus` should
return 404, not metrics. Verify ordinary dashboard/login requests still work.

```bash
dc --profile monitoring ps
sudo docker stats --no-stream "$(dc ps -q alloy)"
dc logs --tail=50 alloy
```

Review logs privately and redact before sharing. Check for missing collectors,
401/403 responses, retry loops, OOMs and missing service samples. Readiness alone
does not prove successful scrapes or remote-write delivery.

Resource ceilings: **384 MiB memory, no extra swap, half of one CPU, 128 tasks**.
These are limits, not a usage forecast; measure under backfill and normal load.
Scrapes have 5,000/10,000-sample limits; exceeding one fails that scrape rather
than allowing unchecked growth. Remote-write uses at most two queue shards.
WAL retention is one hour with
15-minute truncation checks and segment overhead, **not a hard 1 GiB disk cap**.
Long outages can lose older monitoring samples; this is not an audit log.
Watch host disk/Cloud usage and stop Alloy if overhead threatens application
health. Do not increase limits blindly.

## Rollback and token rotation

To disable only monitoring:

```bash
dc --profile monitoring stop alloy
```

Keep `alloy_data` and `.env.monitoring`. No database or application volume is
removed. Retention still applies to buffered metrics when Alloy starts again.

If the installed configuration itself must be reverted, stop Alloy first,
restore Compose and Caddy from the saved backup, restore the prior Alloy
directory if there was one, validate Caddy, then reload it. Do not restore
`.env.production` or run `docker compose down -v`.

For token rotation, edit only `.env.monitoring`, then:

```bash
dc --profile monitoring up -d --no-deps --force-recreate alloy
```

Confirm fresh Cloud samples and no authorization errors **before** revoking
the old token. A simple restart does not reread service env-file values.

## Teammate / CI checks (no production credentials)

```bash
bash scripts/verify-monitoring-stack.sh
./scripts/verify-production-stack.sh
./mvnw -B clean verify
```

The monitoring script resolves Compose in a temporary directory, checks both
profiles, validates the pinned Alloy config, rejects missing credentials,
scrapes real host/container samples and checks public Caddy restrictions.
It uses dummy credentials, isolated containers and no network for Alloy.
The API tests verify an actual Prometheus response plus existing auth rules.

Local Docker Desktop/OrbStack tests observe their Linux VM, not macOS/Windows.
They do not replace the owner's Linux production and Grafana ingestion checks.

References: [Alloy container metrics](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.cadvisor/),
[Alloy host metrics](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.unix/),
[Compose env files](https://docs.docker.com/reference/compose-file/services/#env_file).
