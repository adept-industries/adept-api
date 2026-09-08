# Adept Monitoring Setup — Owner Runbook

This document covers PR 1: the Grafana Alloy collector, host metrics, container
metrics, and API metrics. PRs 2 and 3 extend this foundation.

> **Teammate scope:** implement code, configuration, and tests only.
> **Owner scope:** apply this runbook on AWS after PRs are merged.

---

## What PR 1 installs

| Component | What it collects |
|---|---|
| Alloy node exporter | Host CPU, memory, swap, disk, network |
| Alloy cAdvisor | Docker container CPU, memory, restarts |
| API scrape | Spring request rates, latency, JVM, HikariCP pool |

Data flows to **Grafana Cloud**. No separate Prometheus or Grafana server is
installed on the VM.

---

## Prerequisites

- Docker Compose v2.20 or later (`docker compose version`)
- A Grafana Cloud stack (free tier or above)
- SSH access to the production VM at `/opt/adept/`

---

## Step 1 — Create the Grafana Cloud stack and token

1. Log in to [grafana.com](https://grafana.com) and open your organisation.
2. Under **My Stacks**, create or select a stack.
3. From the stack detail page, note:
   - **Prometheus remote-write URL** → `GRAFANA_CLOUD_METRICS_URL`
   - **Prometheus instance ID** (numeric) → `GRAFANA_CLOUD_METRICS_USER`
   - **Loki push URL** → `GRAFANA_CLOUD_LOGS_URL` *(used in PR 3)*
   - **Loki instance ID** (numeric) → `GRAFANA_CLOUD_LOGS_USER` *(used in PR 3)*
4. Under **Access Policies**, create a new Access Policy with only
   **metrics:write** permission *(add logs:write in PR 3)*. Generate a token.
   This token is `GRAFANA_CLOUD_TOKEN`.

> **Security:** the token has write-only scope. It cannot read data, manage
> dashboards, or administer users.

---

## Step 2 — Install files on AWS

Merging the PR does **not** upload files to the VM. The owner must copy them manually.

SSH to the VM and run:

```bash
# Copy configuration files from the repository clone or a deployment artefact.
# Adjust the source path to match how you receive the files.
cp -r adept-api/infra/aws/alloy /opt/adept/alloy
cp    adept-api/infra/aws/compose.yaml /opt/adept/compose.yaml
```

Verify the mapping:

| Repository path | VM path |
|---|---|
| `infra/aws/alloy/config.alloy` | `/opt/adept/alloy/config.alloy` |
| `infra/aws/compose.yaml` | `/opt/adept/compose.yaml` |
| `infra/aws/.env.monitoring.example` | Reference only — do not copy as-is |

> **Reminder:** image-tag deployments (GitHub Actions) update image references
> in `.env.production` and recreate containers, but they do **not** upload
> changed `compose.yaml` or `alloy/config.alloy` to AWS. You must copy these
> manually whenever they change.

---

## Step 3 — Create `/opt/adept/.env.monitoring`

```bash
# On the production VM
cp /opt/adept/alloy/config.alloy /tmp/  # not needed; just verifying file exists
nano /opt/adept/.env.monitoring
```

Fill in real values (see Step 1). Example structure:

```env
GRAFANA_CLOUD_METRICS_URL=https://prometheus-prod-XX-XXXX.grafana.net/api/prom/push
GRAFANA_CLOUD_METRICS_USER=123456
GRAFANA_CLOUD_LOGS_URL=https://logs-prod-XX-XXXX.grafana.net/loki/api/v1/push
GRAFANA_CLOUD_LOGS_USER=789012
GRAFANA_CLOUD_TOKEN=<your-scoped-token>
```

Restrict permissions immediately:

```bash
chmod 600 /opt/adept/.env.monitoring
ls -la /opt/adept/.env.monitoring   # verify: -rw------- root root
```

---

## Step 4 — Enable monitoring

```bash
cd /opt/adept

# Start only the Alloy container; do not recreate application services.
docker compose --profile monitoring up -d alloy
```

---

## Step 5 — Validate (no secrets are printed by these commands)

```bash
# 1. Confirm Alloy is running and healthy
docker compose --profile monitoring ps alloy

# 2. Check recent logs for startup errors
docker compose logs --tail=50 alloy

# 3. Verify Alloy reached the ready endpoint
docker compose exec alloy wget -q -O - http://localhost:12345/-/ready

# 4. Verify the API metrics endpoint is reachable from Alloy's network
#    (replace api with the actual container name if different)
docker compose exec alloy wget -q -O /dev/null http://api:8080/actuator/prometheus
echo "Exit: $?"  # should be 0

# 5. Check resource usage — Alloy should use < 5% CPU and < 150 MB RAM
docker stats --no-stream alloy

# 6. Confirm metrics appear in Grafana Cloud Explore within 2 minutes
#    - Open grafana.com → your stack → Explore
#    - Query: {job="adept-api"} or node_cpu_seconds_total
```

---

## Step 6 — Verify no public ports were added

```bash
# Only ports 80 and 443 should be listed (Caddy).
docker compose --profile monitoring ps --format "table {{.Name}}\t{{.Ports}}"
```

---

## Rollback

To stop monitoring without affecting the application:

```bash
cd /opt/adept
docker compose --profile monitoring stop alloy
docker compose --profile monitoring rm -f alloy
```

The `alloy_data` Docker volume (WAL) is preserved so metrics are not lost if
you re-enable monitoring later. To delete it permanently:

```bash
docker volume rm adept-production_alloy_data
```

Monitoring can be removed entirely without deleting `.env.production`,
PostgreSQL data, or any application volume.

---

## Rotating Grafana credentials

1. Create a new token in Grafana Cloud Access Policies.
2. Update `/opt/adept/.env.monitoring` with the new token.
3. Recreate the Alloy container so it reads the updated file:

```bash
cd /opt/adept
docker compose --profile monitoring up -d --force-recreate alloy
```

4. Revoke the old token in Grafana Cloud.

---

## Docker socket security

Alloy mounts `/var/run/docker.sock` to collect cAdvisor container metrics.
Mounting the socket read-only (`:ro`) prevents file writes to the socket path,
but it does **not** restrict which Docker API operations Alloy can perform via
the daemon. This is a well-understood trade-off for container monitoring.

Mitigations in place:
- Alloy runs with the minimal Linux capabilities granted by Docker's default
  runtime (`--cap-drop=ALL` is not set but no `--cap-add` is used either).
- The socket is not exposed to the public internet.
- Alloy is pinned to a specific image digest so its behaviour is reproducible.

Review this risk before enabling monitoring on a high-security deployment.

---

## Checking Grafana Cloud usage limits

Free tier limits (as of 2026): 10,000 active metric series, 50 GB logs/month.
This PR enables only metrics; PR 3 adds logs.

To check current usage:
- Grafana Cloud → your stack → **Usage & Billing**

Contact the owner before enabling paid features or upgrading the plan.
