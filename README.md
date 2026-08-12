# Adept API

The Adept API is the Java backend and sole owner of the shared PostgreSQL database schema.

## Overview & Current Status

Phase 2 authentication, session management, workspace switching, workspace management, project grouping, and OpenAPI contract generation are fully implemented:
- **Framework & Runtime**: Spring Boot 4.1 on Java 25, Flyway V1–V9, Hibernate validation, PostgreSQL 18.
- **Authentication**: JWT access tokens, HttpOnly refresh cookies (`adept_refresh`), CSRF protection (`XSRF-TOKEN` / `X-XSRF-TOKEN`), BCrypt password hashing.
- **Workspace Management**: Managers can create additional tenant workspaces, switch between memberships, update workspace settings, and request controlled workspace deletion.
- **Projects**: Projects group repositories inside one workspace. Managers manage projects and repository links; Leads see only projects containing repositories assigned to them.
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

- Users belonging to multiple workspaces receive `workspaceSelectionRequired: true` during login.
- Switch active workspace context via `POST /api/v1/auth/switch-workspace/{workspaceId}` (requires `adept_refresh` cookie & `X-XSRF-TOKEN` header).
- View accessible workspaces via `GET /api/v1/workspaces` and current workspace via `GET /api/v1/workspaces/current`.
- Create another workspace with `POST /api/v1/workspaces`; the creator becomes its Manager.
- Manage project groupings through `/api/v1/projects`. Project choice filters repository-based views but does not replace workspace switching.
- Controlled workspace deletion (`DELETE /api/v1/workspaces/current`) requires Manager role, BCrypt password reauthentication, and exact confirmation-slug matching.
- A successful deletion request marks the workspace `DELETING`, suspends its active integrations, and enqueues one pending `DELETE_WORKSPACE` job. Phase 2 does not include the job handler, so it does not hard-delete the workspace.

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

## Database Ownership

Flyway files under `src/main/resources/db/migration` are the schema source of truth. Hibernate uses `ddl-auto: validate`. Never edit an already-shared migration. Generate local ERD with `./scripts/generate-erd.sh`.

The authentication/workspace baseline ends at V8. The approved project-grouping extension is isolated in V9, and the migration inventory is V1–V9.
