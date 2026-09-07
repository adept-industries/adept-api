# Adept AWS Terraform

This directory contains two independent Terraform roots:

- `bootstrap` will own the remote-state bucket and account budget.
- `environments/staging` will own the staging Lightsail infrastructure.

Authenticate with short-lived console credentials before running Terraform:

```bash
aws login
aws sts get-caller-identity
```

Never create an access key for Terraform. Never commit state, plans, real
variable files, backend configuration, or application secrets.

Before planning the bootstrap root, copy `bootstrap/terraform.tfvars.example`
to the ignored `bootstrap/terraform.tfvars` and replace the notification email.
The bootstrap plan creates only the protected state bucket and cost budget.

The staging root defaults `runtime_enabled` to `false`. Its Lightsail instance,
imported public key, firewall, static IP, IP attachment, and application DNS
record all use one shared gate. Keep it false until the Paid-plan upgrade and a
reviewed runtime plan. The active and retained instances have `prevent_destroy`:
setting the runtime gate back to false is not a maintenance/shutdown procedure.
Stop the instance operationally when needed; removal needs a separate reviewed
change after backups and explicit approval. The public Route 53 hosted zone stays
outside the gate and uses `prevent_destroy` because registrar delegation must
survive runtime replacement or shutdown. Alarms and instance backup access must
use the runtime gate; persistent recovery storage stays outside it.

The canonical public application URL is `https://adeptindustries.dev`. The
domain is registered outside AWS; its registrar nameservers remain unchanged
until Terraform creates and outputs the reviewed AWS DNS zone. Never delegate
the domain using nameservers copied from a plan: apply the hosted zone first,
then use the final `route53_name_servers` output and verify DNS before changing
the registrar.

## Snapshot-based size upgrades

Create the larger VM from a clean snapshot, preserving data, SSH access and the
existing static IP. Do not use a bundle change followed by a normal apply to
upgrade: that can replace the instance with a fresh disk.

Set `instance_name` to the new name and `bootstrap_instance = false` in the
ignored live variable file. Move the original instance state into
`aws_lightsail_instance.retained["OLD_NAME"]`, describing that original instance
in `retained_instances`, and import the new VM at the active instance address.
Reconcile firewall ownership and the static-IP attachment as part of the cutover.
Keep a protected state backup and review the entire resulting plan before any
apply. These resources do not manage whether a VM is running or stopped.

`allow_public_ssh` defaults to false. It is an explicit, temporary exception for
preserving a deployment that already uses public, key-only SSH with GitHub-hosted
runners. It is not needed for runners with trusted fixed egress addresses. The
September 2026 migration preserves the existing public rule; changing runner
network access and restricting SSH should be reviewed separately.

Keep retained instances stopped. They are still billed until explicitly deleted;
do not delete or remove their protection until the new system is validated. Once
the new database accepts writes, the retained disk is stale: preserve those newer
writes before any rollback.
