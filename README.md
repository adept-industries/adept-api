# Adept API

The Adept API is the Java backend and sole owner of the shared PostgreSQL database schema.

## Overview & Current Status

The API implementation through Phase 6 covers authentication, workspaces, projects, provider integrations, secure webhook ingestion, repository-scoped DORA metrics, and complete OpenAPI contract generation.
- **Framework & Runtime**: Spring Boot 4.1 on Java 25, Flyway V1–V15, Hibernate validation, PostgreSQL 18.
- **Authentication**: JWT access tokens, HttpOnly refresh cookies (`adept_refresh`), CSRF protection (`XSRF-TOKEN` / `X-XSRF-TOKEN`), BCrypt password hashing.
- **Workspace Management**: Managers can create additional tenant workspaces, switch between memberships, update workspace settings, and request controlled workspace deletion.
- **Projects**: Projects group tracked, non-archived repositories inside one workspace. Managers can atomically configure project repository links and each included repository's workspace-scoped Jira mappings; Leads see only projects containing repositories assigned to them. Jira mappings remain repository-level settings when a repository is removed from a project.
- **Integrations & Webhooks**: Managers connect GitHub and Jira, configure tracked repositories and mappings, and receive verified, duplicate-safe provider deliveries that are stored with durable processing jobs in one transaction.
- **DORA Metrics**: Summary and series endpoints enforce Manager/Lead repository scope, use half-open time ranges, aggregate exact `dora-v3` observations, and report calculation time, workspace timezone, version, and staleness.
- **PR Risk Foundation**: The API owns the existing feature/prediction schema and the frozen `jitfine-pr-features-v1` / `jitfine-expert-pr-risk-mvp-v1` read contract. Verified GitHub deliveries remain durable jobs for `adept-engine`; the API does not synchronously score webhook payloads or expose an unauthenticated risk stream.
- **OpenAPI**: Contracts configured via `springdoc-openapi` and exported deterministically to `docs/openapi/adept-api-v1.json`.

## Local Sibling Layout

```text
adept-local/
├── .env
├── adept-api/
├── adept-engine/
└── adept-frontend/
```

## Start PostgreSQL and Mailpit

From `adept-local`:

```bash
docker compose --env-file .env \
  -f adept-api/infra/local/compose.yaml \
  up -d postgres mailpit
```

PostgreSQL is published on port 5432 by default. Mailpit SMTP runs on port 1025 and its web inbox UI is at <http://localhost:8025>.

## Run the API locally

```bash
cd adept-api
set -a
source ../.env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Health endpoint: <http://localhost:8080/actuator/health>

## Authentication, CSRF & Workspace Flow

### 1. CSRF Bootstrap & Unsafe Requests

- Request `GET /api/v1/auth/csrf` to bootstrap the `XSRF-TOKEN` cookie.
- Pass the token value in header `X-XSRF-TOKEN` for all state-changing HTTP operations (`POST`, `PUT`, `PATCH`, `DELETE`).

### 2. Account Lifecycle

1. Create an account and its first workspace with `POST /api/v1/auth/signup`.
2. Copy the one-time token from Mailpit and submit it to `POST /api/v1/auth/verify-email`.
3. Use `POST /api/v1/auth/resend-verification` when another verification email is needed.
4. Sign in with `POST /api/v1/auth/login`.
5. Use `POST /api/v1/auth/forgot-password` and `POST /api/v1/auth/reset-password` for password recovery.
6. Rotate the browser session with `POST /api/v1/auth/refresh` and end it with `POST /api/v1/auth/logout`.

Verification, resend, password-reset, login, refresh, and logout requests must use the current CSRF cookie/header pair. Responses from resend and forgot-password are deliberately generic.

### 3. Access and Refresh Tokens

- Authenticated requests present `Authorization: Bearer <accessToken>`.
- Access JWTs expire in 15 minutes and must be held only in frontend memory, never local storage or a persistent cookie.
- The seven-day `adept_refresh` token is stored in the `HttpOnly`, `Secure`, `SameSite=Strict` cookie. Browser JavaScript cannot read it.
- `POST /api/v1/auth/refresh` rotates the refresh token and returns a new memory-only access token when a workspace can be selected.
- Authentication and workspace responses use `Cache-Control: no-store`.

### 4. Workspace Selection & Switching Rules

- Users with multiple workspaces, or no active workspace after deleting their last one, receive `workspaceSelectionRequired: true` during login.
- Switch active workspace context via `POST /api/v1/auth/switch-workspace/{workspaceId}` (requires `adept_refresh` cookie & `X-XSRF-TOKEN` header).
- Accounts with no active workspace can create one through `POST /api/v1/auth/workspaces`; this refresh-cookie flow restores a Manager session without creating a second account.
- View accessible workspaces via `GET /api/v1/workspaces` and current workspace via `GET /api/v1/workspaces/current`.
- Create another workspace with `POST /api/v1/workspaces`; the creator becomes its Manager.
- Manage project groupings through `/api/v1/projects`. Project choice filters repository-based views but does not replace workspace switching.
- Controlled workspace deletion (`DELETE /api/v1/workspaces/current`) requires Manager role, recent password or Google authentication, and exact confirmation-slug matching.
- A successful deletion request marks the workspace `DELETING`, suspends its active integrations, and enqueues one unscoped `DELETE_WORKSPACE` job. The engine hard-deletes only a workspace already in `DELETING`; cascading tenant data is removed while global user/session data remains intact.

## Jira Dynamic Webhooks

- The Jira OAuth application must grant `read:jira-work`, `manage:jira-webhook`, and `offline_access`; `read:jira-user` is also requested for the current catalog flow.
- Completing OAuth registers an Atlassian dynamic webhook for issue created, updated, and deleted events. The callback is `/api/v1/webhooks/jira/{integrationId}?token={opaqueToken}`.
- The 32-byte callback token is generated once and sent only to Atlassian. Adept stores a domain-separated, peppered HMAC-SHA-256 in `jira_integrations.webhook_token_hash`, compares hashes in constant time before parsing a payload, and never stores the query token in webhook headers or job payloads.
- Authenticated issue events are retained only when `issue.fields.project.id` belongs to that integration and the Jira project is tracking-enabled. Unsupported, unknown, and disabled-project payloads are discarded before raw-event persistence.
- `X-Atlassian-Webhook-Identifier` is the idempotency key when present. A body digest is the fallback, and exactly one raw event and processing job are retained for a repeated delivery.
- Dynamic webhooks expire after 30 days. Adept verifies that a stored ID is still present in Atlassian's paginated webhook catalog before refreshing it, schedules one retryable renewal five days before expiry, and reuses the existing future renewal instead of creating duplicates. A reconnect replaces a missing remote webhook, and callback failures compensate newly registered webhooks so their one-time tokens are not orphaned.
- Jira integrations connected before V12 have no callback-token hash or API-registered dynamic webhook. V12 marks those rows `ERROR`; a Manager must disconnect and reconnect each one once after rollout.
- Managers can request an idempotent durable catalog refresh with `POST /api/v1/integrations/jira/{integrationId}/sync`. The engine paginates Atlassian projects and preserves tracking choices for projects that still exist.

## OpenAPI Contract Generation

With the API running under the `local` profile, Swagger UI is available at <http://localhost:8080/swagger-ui/index.html> and the live JSON document is at <http://localhost:8080/v3/api-docs>.

To export the OpenAPI specification deterministically to `docs/openapi/adept-api-v1.json`:

```bash
./scripts/export-openapi.sh
```

The script fetches `/v3/api-docs` from a running server or executes a Spring context generation test, validates and sorts the JSON in a temporary file, and atomically replaces `docs/openapi/adept-api-v1.json`. `OpenApiContractTest` compares live test output with that committed file, so contract drift fails CI.

## Testing

Integration tests require Docker running for PostgreSQL Testcontainers:

```bash
./mvnw clean verify
```

After the complete `CI` workflow succeeds for a push to `main`, the publish
workflow builds Linux AMD64 and pushes exactly one immutable image tag:

```text
ghcr.io/adept-industries/adept-api:sha-<full-commit>
```

Pull-request runs, failed CI runs, and non-main branches never publish. A
serialized production job deploys that exact image to AWS Lightsail, waits for
API health, and only then reports a terminal GitHub Deployment status for the
tested SHA and the `production` environment. The workflow uses GitHub's
short-lived `GITHUB_TOKEN` for GHCR and Deployment API access plus the existing
`LIGHTSAIL_HOST`, `LIGHTSAIL_USER`, and `LIGHTSAIL_SSH_KEY` secrets; no PAT is
required.

## Database Ownership

Flyway files under `src/main/resources/db/migration` are the schema source of truth. Hibernate uses `ddl-auto: validate`. Never edit an already-shared migration. Generate local ERD with `./scripts/generate-erd.sh`.

The authentication/workspace baseline ends at V8, project grouping is isolated in V9, Google authentication is isolated in V10, recent-authentication session metadata is isolated in V11, hashed Jira webhook credentials are isolated in V12, Phase 6 metric correctness support is isolated in V13, project-level Jira mappings are isolated in V14, and the project issue dashboard schema is isolated in V15. The migration inventory is V1–V15.
