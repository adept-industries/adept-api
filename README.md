# Adept API

The Adept API is the Java backend and sole owner of the shared PostgreSQL database schema.

## Overview & Current Status

Phase 2 authentication, session management, workspace switching, workspace management, and OpenAPI contract generation are fully implemented:
- **Framework & Runtime**: Spring Boot 4.1 on Java 25, Flyway V1–V8, Hibernate validation, PostgreSQL 18.
- **Authentication**: JWT access tokens, HttpOnly refresh cookies (`adept_refresh`), CSRF protection (`XSRF-TOKEN` / `X-XSRF-TOKEN`), BCrypt password hashing.
- **Workspace Management**: Multi-workspace membership support, workspace switching, PATCH workspace settings, and controlled workspace deletion flow (`DELETE /api/v1/workspaces/current`).
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

### 2. Bearer Access Tokens
- Authenticated requests must present `Authorization: Bearer <accessToken>`.
- JWT access tokens expire in 15 minutes. Use `POST /api/v1/auth/refresh` with `adept_refresh` HttpOnly cookie to obtain a fresh access token.

### 3. Workspace Selection & Switching Rules
- Users belonging to multiple workspaces receive `workspaceSelectionRequired: true` during login.
- Switch active workspace context via `POST /api/v1/auth/switch-workspace/{workspaceId}` (requires `adept_refresh` cookie & `X-XSRF-TOKEN` header).
- View accessible workspaces via `GET /api/v1/workspaces` and current workspace via `GET /api/v1/workspaces/current`.
- Controlled workspace deletion (`DELETE /api/v1/workspaces/current`) requires Manager role, BCrypt password reauthentication, exact confirmation slug matching, and enqueues a `DELETE_WORKSPACE` background worker job.

## OpenAPI Contract Generation

To export the OpenAPI specification deterministically to `docs/openapi/adept-api-v1.json`:

```bash
./scripts/export-openapi.sh
```

The script fetches `/v3/api-docs` from a running server or executes a Spring context generation test, formats the JSON deterministically with `jq --sort-keys`, and outputs `docs/openapi/adept-api-v1.json`.

## Testing

Integration tests require Docker running for PostgreSQL Testcontainers:

```bash
./mvnw clean verify
```

## Database Ownership

Flyway files under `src/main/resources/db/migration` are the schema source of truth. Hibernate uses `ddl-auto: validate`. Never edit an already-shared migration. Generate local ERD with `./scripts/generate-erd.sh`.
