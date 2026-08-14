# Adept production stack

This directory is the server-side Compose entry point for
`https://adeptindustries.dev`. Caddy is the only public service. The frontend,
API, engine processes, and PostgreSQL are reachable only on the Compose network.

The committed example contains placeholders, never production secrets. On the
server, create the ignored runtime file and restrict its permissions:

```bash
cp infra/aws/.env.production.example infra/aws/.env.production
chmod 600 infra/aws/.env.production
```

Replace every placeholder and every example image tag. The three application
images must use immutable `sha-<full-commit>` tags; never deploy `latest`.

Validate without printing the secret-expanded configuration:

```bash
./scripts/verify-production-stack.sh
```

This checks the example configuration, immutable image-tag format, public-port
boundary, and Caddy syntax. On the server, separately validate the real ignored
environment without printing its expanded values:

```bash
docker compose \
  --env-file infra/aws/.env.production \
  -f infra/aws/compose.yaml \
  config --quiet
```

Do not start this stack until all three application images exist, the runtime
Terraform plan has been reviewed and applied, and the domain A record resolves
to the attached static IP. Caddy then obtains and renews HTTPS automatically.

The PostgreSQL, Caddy data, and Caddy configuration volumes are persistent.
They survive normal container replacement, but the PostgreSQL volume is not a
backup. Add and test the documented logical backup flow before storing important
demonstration data.
