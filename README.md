# Adept API

The Adept API is the Java backend and the only owner of the shared PostgreSQL schema.

## Current status

The Phase 1 API/database foundation is implemented: Spring Boot 4.1 on Java 25, Flyway V1–V7, Hibernate validation, PostgreSQL 18, Testcontainers tests, health endpoints, and a Java 25 container image. Authentication and business endpoints begin in later phases.

## Local sibling layout

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

PostgreSQL is published on port 5432 by default. Mailpit SMTP is on 1025 and its browser inbox is at <http://localhost:8025>.

## Run the API on the host

```bash
cd adept-api
set -a
source ../.env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Health: <http://localhost:8080/actuator/health>

## Test

Docker must be running because integration tests use disposable PostgreSQL 18 Testcontainers databases.

```bash
./mvnw -B clean verify
```

## Build the API image

From `adept-local`:

```bash
docker compose --env-file .env \
  -f adept-api/infra/local/compose.yaml \
  --profile full build api
```

## Database ownership

Flyway files under `src/main/resources/db/migration` are the schema source of truth. Hibernate uses `ddl-auto: validate`; do not change it to schema creation/update. Never edit an already-shared migration. Generate the local ERD with `./scripts/generate-erd.sh`.
