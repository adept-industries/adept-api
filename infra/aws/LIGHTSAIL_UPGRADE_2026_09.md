# September 2026 Lightsail upgrade

This deployment serves production despite the existing `staging` resource names.

## Verified post-upgrade resources

- Region/zone: `ap-south-1` / `ap-south-1a`.
- Active VM: `adept-staging-app-4gb-20260907`, bundle `medium_3_1`
  (4 GB RAM, 2 vCPUs, 80 GB disk; catalog price $24/month at migration).
- Former 2 GB VM: `adept-staging-app`, bundle `small_3_1`, deleted with owner
  approval on 2026-09-07 after the new system was validated.
- Existing static IP: `adept-staging-ip`, `3.111.250.16`.
- Domain: `https://adeptindustries.dev`; no DNS or OAuth URL change.
- SSH deployment user/key unchanged. The new VM has new SSH **host** keys;
  verify them through the Lightsail API before updating known-host entries.
- Automatic snapshots: daily at 03:00 UTC.
- Manual pre-upgrade snapshot: `adept-staging-before-4gb-20260907`.

The old VM is no longer retained or billable as an instance. The manual recovery
snapshot remains available; snapshot storage is still billable.

## Data protection and validation

The migration exported PostgreSQL custom-format dumps, cluster roles, the
production configuration directory and Terraform state to a private folder on
the operator's Mac, outside Git. Keep these files private: they contain user data,
credentials and encryption keys. The operational handoff provides the location.

Both the initial and final database dumps were restored into an isolated local
PostgreSQL instance. The final restore and the new VM matched all 35 public-table
row counts, with Flyway schema version 15. The production `.env.production` hash
and application image versions matched before traffic was moved.

During migration, all three image-publishing workflows were paused, application
writes and workers were stopped, and PostgreSQL was cleanly stopped before the
VM snapshot. Docker was disabled in the snapshot to avoid duplicate processing
on the clone; it is explicitly re-enabled on the active VM after verification.

Lightsail prepends a `/bin/sh` wrapper to launch scripts. Use POSIX-compatible
commands (for example `set -eu`) in a short launch guard; an embedded Bash shebang
does not change that wrapper's interpreter. The new VM's initial shell error was
repaired, and a controlled reboot then reported clean cloud-init and healthy
automatically restarted application services.

## Terraform adoption

Use the project's pinned Terraform 1.15.x, not an unsupported globally installed
version. Actual variable files, state and saved plans are ignored and must never
be committed. The active live variable file selects the new name and bundle,
sets `bootstrap_instance = false`, and now sets `retained_instances = {}` after
the approved retirement described below.

The old VM and firewall were moved to their `retained["adept-staging-app"]`
Terraform addresses. The larger VM was imported at `app["primary"]`. The cutover
plan adopts the already-configured new firewall and moves the existing static-IP
attachment; it does not delete either server, disk, IP allocation or DNS record.

The existing public IPv4 SSH rule is preserved explicitly through
`allow_public_ssh = true`, with password authentication disabled. Restricting SSH
while preserving GitHub-hosted deployment access needs a separate reviewed
networking change. PostgreSQL and application ports are not public.

## Completion checks

- Public cutover completed at 2026-09-07 12:13:50 UTC (17:43:50 Asia/Colombo).
- HTTPS homepage returned 200; the public API CSRF endpoint returned its normal 204.
- PostgreSQL, API, frontend, Caddy and engine API were healthy; the worker was running.
- Engine readiness reported schema 15 and the PR-risk model reported ready.
- A full post-upgrade Terraform plan returned exit code 0 (no changes).
- All three publishing workflows were re-enabled; repository secrets were unchanged.
- GitHub's delivery audit found no deliveries in the maintenance window, so no
  GitHub replay was needed. This does not claim a Jira delivery audit.
- The Mac's three old host-key entries for the static IP were backed up and
  replaced with AWS-verified keys; the normal SSH command was tested successfully.
- The 43 pre-existing dead background jobs were already present before the
  migration; this upgrade does not retry or repair unrelated failed jobs.

## Rollback boundary

During migration, before the new database accepted writes, the retained VM and
snapshot provided a rollback path. The old VM has since been deleted; recovery
now requires restoring a backup or creating a VM from a retained snapshot.

After the new database accepts writes, the old database is stale. Do not simply
move the IP back: first pause writes and preserve or reconcile the newer data.
Docker is disabled in the manual migration snapshot and must be enabled
deliberately on a restored VM. Do not run duplicate workers. Verify the restored
VM's SSH host keys. Never run `docker compose down -v`, delete volumes, or apply a
destructive Terraform plan as part of routine recovery.

## Approved old-VM retirement

- Owner-authorized deletion of `adept-staging-app` succeeded on 2026-09-07 at
  12:57:25 UTC (18:27:25 Asia/Colombo).
- The deleted resources were the old VM, its system disk/firewall and its seven
  automatic snapshots. The manual `adept-staging-before-4gb-20260907` snapshot
  and verified external database/configuration backups were preserved.
- The new 4 GB VM, static IP, DNS, deployment key and production data were not
  changed. The latest API image deployment had succeeded before retirement.
- The old entry was removed from the ignored live `retained_instances` map.
  A reviewed refresh-only plan reconciled the already-deleted VM/firewall records
  and the new VM's existing static-IP attributes, without modifying AWS resources.
  Instance deletion protections in Terraform were not weakened.
- Post-retirement validation: all six containers running, configured health checks
  healthy, engine schema 15 and model ready, homepage HTTP 200, API CSRF HTTP 204,
  and a full Terraform plan with no changes.
