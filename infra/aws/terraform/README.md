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
reviewed runtime plan. In an emergency, setting it back to false plans those
runtime resources for removal together. The public Route 53 hosted zone stays
outside the gate and uses `prevent_destroy` because registrar delegation must
survive runtime replacement or shutdown. Alarms and instance backup access must
use the runtime gate; persistent recovery storage stays outside it.

The canonical public application URL is `https://adeptindustries.dev`. The
domain is registered outside AWS; its registrar nameservers remain unchanged
until Terraform creates and outputs the reviewed AWS DNS zone. Never delegate
the domain using nameservers copied from a plan: apply the hosted zone first,
then use the final `route53_name_servers` output and verify DNS before changing
the registrar.
