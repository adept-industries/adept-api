# Adept Production Monitoring Runbook — Setup, Operation, and Rollback

This runbook covers the end-to-end rollout of Adept's Prometheus, Grafana Alloy, and Grafana Cloud monitoring stack across the single-VM AWS Lightsail environment.

**Production Owner Notice:**
Only the production owner has AWS SSH and Grafana Cloud administrative access. The teammate prepares code, configurations, templates, and automated tests. The owner reviews the files, provisions Grafana Cloud credentials, creates `/opt/adept/.env.monitoring`, and applies the changes on AWS.

Merging pull requests deploys application container images via CI workflows, but does **not** automatically upload or apply Docker Compose, Caddy, or Alloy configuration files to the AWS host.

---

## 1. Architecture and Component Overview

```text
+-------------------------------------------------------------------------------+
| AWS Lightsail VM (4 GB RAM, Ubuntu Linux)                                     |
|                                                                               |
|  +------------+       +------------+       +---------------+                  |
|  |   Caddy    |  -->  |    API     |  -->  | Engine Worker |                  |
|  |  (:80/:443)|       |  (:8080)   |       |  (:8001 /m)   |                  |
|  +------------+       +------------+       +---------------+                  |
|        |                     |                     |                          |
|  (Docker Logs)         (/actuator/prometheus)   (/metrics)                    |
|        \                     |                     /                          |
|         +--------------------+--------------------+                           |
|                              v                                                |
|                   +---------------------+                                     |
|                   |    Grafana Alloy    | (Resource bounded: 384m RAM, 0.5 CPU|
|                   | (Host Agent/Scraper)|  Log redaction & WAL buffering)     |
|                   +---------------------+                                     |
+------------------------------|------------------------------------------------+
                               | HTTPS Remote-Write (Metrics & Loki Logs)
                               v
+-------------------------------------------------------------------------------+
| Grafana Cloud (Hosted Observability)                                          |
|                                                                               |
|  - Hosted Prometheus (Metrics storage & PromQL engine)                        |
|  - Hosted Loki (Sanitized container & operational logs)                       |
|  - Dashboards (Infrastructure, Application, Worker & Queue)                   |
|  - Unified Alerting (Operational alert rules & email notifications)           |
|  - Synthetic Monitoring (External website & API uptime probes)                |
+-------------------------------------------------------------------------------+
```

### Metrics & Scrapes Contract
| Target | Endpoint / Exporter | Private Port | Scrape Interval | Key Metrics |
|---|---|---|---|---|
| **Linux Host** | unix collector (node exporter) | Internal | 60s | CPU, RAM (`node_memory_*`), swap, root disk (`node_filesystem_*`), load |
| **Containers** | cadvisor collector | Internal | 60s | Container CPU, memory working set, network, disk I/O |
| **API** | `/actuator/prometheus` | `api:8080` (private) | 60s | Request rate/latency/5xx (`http_server_requests_seconds_*`), JVM heap, HikariCP pool |
| **Engine Worker** | `/metrics` | `engine-worker:8001` (private) | 60s | Configured threads, live threads (`adept_engine_worker_thread_alive`), poll timestamps, queue size/oldest ready age, dead-letter jobs, completed/failed job throughput |
| **Alloy Collector** | Internal listener | `127.0.0.1:12345` | 60s | Collector process memory/CPU, remote-write queue, drop counters |

### Security & Privacy Protections
1. **Network Isolation**: Only Caddy binds to public host ports (80/443). The API Actuator endpoint (`/actuator/prometheus`) and Engine Worker metrics endpoint (`:8001/metrics`) are accessible only over the private Docker network (`adept_network`).
2. **Log Redaction**: Alloy pipelines filter and scrub authorization headers (`Bearer ...`), API tokens, passwords, cookies, credit card patterns, and personal emails before shipping logs to Grafana Cloud Loki. Raw webhook bodies and customer payload data are excluded.
3. **Secret Segregation**: Real monitoring credentials reside exclusively in `/opt/adept/.env.monitoring` (mode `600`) and are passed only to the Alloy container. The application `.env.production` is never mounted or exposed to Alloy.

---

## 2. Requirements & Prerequisites

- **Host**: Linux host with Docker Engine and Docker Compose **2.24.0 or newer** (`env_file.required` support).
- **Host Paths**: Docker socket `/var/run/docker.sock`, `/var/lib/docker`, `/sys`, and containerd socket `/run/containerd/containerd.sock` (or `/var/run/docker/containerd/containerd.sock`).
- **Grafana Cloud Account**:
  - Prometheus remote-write endpoint URL & instance username (numeric ID).
  - Loki remote-write endpoint URL & instance username (numeric ID).
  - Stack-scoped Access Policy Token with permissions `metrics:write` and `logs:write`.
  - Service Account Token with Alerting/Dashboard provisioning permissions (if applying via Terraform).

---

## 3. Staging and File Installation on AWS

### File Mapping
| Repository Source | Target Path on VM | Permissions | Description |
|---|---|---|---|
| `infra/aws/compose.yaml` | `/opt/adept/compose.yaml` | `644` | Docker Compose stack with monitoring profile & worker port 8001 |
| `infra/aws/Caddyfile` | `/opt/adept/Caddyfile` | `644` | Caddy reverse proxy with blocked public actuator/metrics paths |
| `infra/aws/alloy/config.alloy` | `/opt/adept/alloy/config.alloy` | `644` | Alloy scrapers, log collectors, redaction pipelines, remote writes |
| `infra/aws/alloy/start.sh` | `/opt/adept/alloy/start.sh` | `755` | Alloy entrypoint script with token validation |
| `infra/aws/alloy/healthcheck.sh` | `/opt/adept/alloy/healthcheck.sh` | `755` | Alloy container health check script |
| `infra/aws/.env.monitoring.example` | `/opt/adept/.env.monitoring` | `600` | Owner-populated monitoring secrets file |

### Step 1: Stage Reviewed Files to VM
From your local `adept-api` repository checkout:

```bash
# Upload files to staging directory on VM
ssh -i ~/.ssh/adept-staging ubuntu@3.111.250.16 "mkdir -p /tmp/adept-monitoring-rollout/alloy"
scp -i ~/.ssh/adept-staging infra/aws/compose.yaml infra/aws/Caddyfile infra/aws/.env.monitoring.example ubuntu@3.111.250.16:/tmp/adept-monitoring-rollout/
scp -i ~/.ssh/adept-staging infra/aws/alloy/config.alloy infra/aws/alloy/start.sh infra/aws/alloy/healthcheck.sh ubuntu@3.111.250.16:/tmp/adept-monitoring-rollout/alloy/
```

### Step 2: SSH into VM and Create Backup
Connect via SSH:
```bash
ssh -i ~/.ssh/adept-staging ubuntu@3.111.250.16
```

Create a timestamped rollback backup of existing configuration files:
```bash
monitoring_backup_dir=$(sudo mktemp -d /opt/adept/monitoring-backup.$(date +%Y%m%d_%H%M%S).XXXXXX)
sudo cp -a /opt/adept/compose.yaml /opt/adept/Caddyfile "$monitoring_backup_dir/"
if [ -d /opt/adept/alloy ]; then sudo cp -a /opt/adept/alloy "$monitoring_backup_dir/"; fi
printf "Backup saved to: %s\n" "$monitoring_backup_dir"
```

### Step 3: Install Staged Files
```bash
sudo install -d -m 755 /opt/adept/alloy
sudo install -m 644 /tmp/adept-monitoring-rollout/compose.yaml /opt/adept/compose.yaml
sudo cp /tmp/adept-monitoring-rollout/Caddyfile /opt/adept/Caddyfile
sudo install -m 644 /tmp/adept-monitoring-rollout/alloy/config.alloy /opt/adept/alloy/config.alloy
sudo install -m 755 /tmp/adept-monitoring-rollout/alloy/start.sh /opt/adept/alloy/start.sh
sudo install -m 755 /tmp/adept-monitoring-rollout/alloy/healthcheck.sh /opt/adept/alloy/healthcheck.sh
```

### Step 4: Configure `/opt/adept/.env.monitoring`
If the file does not already exist:
```bash
if ! sudo test -e /opt/adept/.env.monitoring; then
  sudo install -m 600 /tmp/adept-monitoring-rollout/.env.monitoring.example /opt/adept/.env.monitoring
fi
sudo nano /opt/adept/.env.monitoring
sudo chmod 600 /opt/adept/.env.monitoring
```

Populate the five variables from your Grafana Cloud stack details:
```env
GRAFANA_CLOUD_METRICS_URL=https://prometheus-prod-XX-prod-us-east-0.grafana.net/api/prom/push
GRAFANA_CLOUD_METRICS_USER=123456
GRAFANA_CLOUD_LOGS_URL=https://logs-prod-XXX.grafana.net/loki/api/v1/push
GRAFANA_CLOUD_LOGS_USER=654321
GRAFANA_CLOUD_TOKEN=glc_eyJ...
```
*(Never commit or print real tokens.)*

---

## 4. Configuration Validation and Service Activation

Define the Compose helper in your SSH session:
```bash
dc() {
  sudo docker compose --env-file /opt/adept/.env.production -f /opt/adept/compose.yaml "$@"
}
```

### Step 1: Non-Destructive Validation
```bash
# 1. Validate Compose syntax quietly (prevents printing interpolated secrets)
dc --profile monitoring config --quiet

# 2. Validate Caddyfile syntax inside the running Caddy container
dc exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

# 3. Validate Alloy River syntax using an isolated one-off run
dc --profile monitoring run --rm --no-deps alloy validate /etc/alloy/config.alloy
```

### Step 2: Apply Changes and Start Services
```bash
# Reload Caddy config (seamless reload, preserves container and active TLS sessions)
dc exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile

# Recreate Engine Worker container to expose private port 8001
dc up -d --no-deps --force-recreate engine-worker

# Recreate and start Grafana Alloy collector
dc --profile monitoring up -d --no-deps --force-recreate alloy
```

### Step 3: Check Local Health & Scrape Endpoints
```bash
# Verify all containers are running
dc --profile monitoring ps

# Run Alloy internal health check
dc exec -T alloy /bin/bash /etc/alloy/healthcheck.sh

# Verify private API Prometheus scrape from Caddy
dc exec -T caddy curl -fsS -o /dev/null http://api:8080/actuator/prometheus

# Verify private Engine Worker metrics scrape from Caddy/Alloy
dc exec -T caddy curl -fsS -o /dev/null http://engine-worker:8001/metrics
```

---

## 5. Grafana Cloud Provisioning (Dashboards, Alerts & Synthetics)

### Option A: Automated Provisioning via Terraform (Recommended)
From your local machine in `infra/aws/grafana/terraform/`:

1. Create `terraform.tfvars` from `terraform.tfvars.example`:
   ```bash
   cp terraform.tfvars.example terraform.tfvars
   ```
2. Configure `terraform.tfvars`:
   ```hcl
   grafana_url               = "https://your-stack.grafana.net"
   prometheus_datasource_uid = "your-prometheus-datasource-uid"
   notification_emails       = ["owner@example.com"]
   environment               = "production"
   alerts_paused             = false
   manage_notification_policy = false # set true if managing root notification policy
   ```
3. Set your service account token in environment:
   ```bash
   export TF_VAR_grafana_auth="glsa_..."
   ```
4. Initialize and apply:
   ```bash
   terraform init
   terraform plan
   terraform apply
   ```

#### Controlled Notification Delivery Test
To test alert email delivery and recovery without touching production:
```bash
terraform apply -var="notification_test_enabled=true" -var="notification_test_firing=true"
# Check email for firing notification
terraform apply -var="notification_test_enabled=true" -var="notification_test_firing=false"
# Check email for recovery notification
terraform apply -var="notification_test_enabled=false"
```

### Option B: Manual UI Import

#### 1. Import Dashboards
Navigate to **Dashboards** -> **New** -> **Import** in Grafana Cloud:
- Upload `infra/aws/grafana/dashboards/infrastructure.json`
- Upload `infra/aws/grafana/dashboards/application.json`
- Upload `infra/aws/grafana/dashboards/worker.json`
- Set folder to **Adept** and choose the Prometheus data source.

#### 2. Configure Contact Point & Template
In **Alerting** -> **Contact points**:
- Create Contact Point: `Adept owner email` with recipient email.
- Set Message Template with title `[{{ .Status }}] Adept: {{ len .Alerts.Firing }} firing, {{ len .Alerts.Resolved }} recovered` and details.

#### 3. Setup External Synthetic Monitoring (Uptime Checks)
In **Testing & Synthetics** -> **Synthetics**:
1. **Public Website Check**:
   - URL: `https://adeptindustries.dev/`
   - Method: `GET`
   - Frequency: `1m`
   - Label: `job = "adept-website"`, `environment = "production"`
   - Assertions: Status code `200` AND Body contains `"Adept"` (verifies frontend is properly served, not a generic 502/503 page).
2. **API Readiness Check**:
   - URL: `https://adeptindustries.dev/api/v1/health` (or health endpoint)
   - Method: `GET`
   - Frequency: `1m`
   - Label: `job = "adept-api-readiness"`, `environment = "production"`
   - Assertions: Status code `200` AND JSON body contains `"status":"UP"` or `"ok":true` (distinguishes API health from frontend SPA fallback).

---

## 6. Live Telemetry Verification & Resource Monitoring

### Metric Queries to Verify in Grafana Explore
- `up{environment="production"}`: Shows `1` for `adept-api`, `adept-engine-worker`, `alloy`, `alloy-node`, and `cadvisor`.
- `adept_engine_worker_configured_threads{environment="production"}`: Equals `2` (or `1`).
- `adept_engine_worker_thread_alive{environment="production"}`: Sum equals configured thread count.
- `adept_engine_queue_ready_jobs{environment="production"}`: Reports non-negative integer (0 is healthy idle).
- `http_server_requests_seconds_count{environment="production"}`: Reports incoming API traffic.
- `node_memory_MemAvailable_bytes{job="alloy-node"}`: Accurately reflects VM memory.

### Log Queries to Verify in Grafana Explore (Loki)
- `{environment="production", service="api"}`: API application logs.
- `{environment="production", service="engine-worker"}`: Worker execution logs.
- `{environment="production", service="caddy"}`: Caddy access/error logs.
- **Privacy verification**: Confirm tokens, authorization headers, passwords, and raw webhook bodies do not appear in log streams.

### Resource & Ceiling Checks on AWS
```bash
# Check container memory & CPU consumption
sudo docker stats --no-stream "$(dc ps -q alloy engine-worker api)"

# Check Alloy log tail for any ingestion errors or retries
dc logs --tail=50 alloy
```

**Alloy Resource Limits:**
- Memory limit: `384 MiB`
- CPU limit: `0.5 core`
- Disk WAL: Bounded in `alloy_data` volume with automatic segment truncation.

---

## 7. Rollback & Maintenance Procedures

### Disabling Monitoring Only
To shut down the monitoring collector without affecting any application services:
```bash
dc --profile monitoring stop alloy
```
*Persistent application data and `alloy_data` volume remain intact.*

### Full Rollback of Configuration Files
If changes to `compose.yaml` or `Caddyfile` need to be reverted:
```bash
# 1. Stop Alloy
dc --profile monitoring stop alloy

# 2. Restore backed up configuration files
sudo cp -a "$monitoring_backup_dir/compose.yaml" /opt/adept/compose.yaml
sudo cp -a "$monitoring_backup_dir/Caddyfile" /opt/adept/Caddyfile
if [ -d "$monitoring_backup_dir/alloy" ]; then
  sudo cp -a "$monitoring_backup_dir/alloy" /opt/adept/
fi

# 3. Validate and reload Caddy
dc exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
dc exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile

# 4. Recreate engine-worker to reset ports if necessary
dc up -d --no-deps --force-recreate engine-worker
```
*(Never restore `.env.production` or run `docker compose down -v`.)*

### Ingestion Token Rotation
When rotating Grafana Cloud ingestion tokens:
1. Update `GRAFANA_CLOUD_TOKEN` in `/opt/adept/.env.monitoring`.
2. Apply changes:
   ```bash
   dc --profile monitoring up -d --no-deps --force-recreate alloy
   ```
3. Check Alloy logs (`dc logs --tail=30 alloy`) and verify new samples arrive in Grafana Cloud.
4. Revoke the old token in Grafana Cloud only after verifying live data.
