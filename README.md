# Adept API

The Adept API is the Java backend and sole owner of the shared PostgreSQL database schema.

## Tech Stack
- **Framework & Runtime**: Spring Boot 4.1 on Java 25, Flyway V1–V18, Hibernate, PostgreSQL 18.
- **Authentication**: JWT access tokens, HttpOnly refresh cookies, CSRF protection.

## Getting Started

1. **Start PostgreSQL and Mailpit:**
   ```bash
   docker compose --env-file ../.env -f infra/local/compose.yaml up -d postgres mailpit
   ```
2. **Run the API locally:**
   ```bash
   set -a && source ../.env && set +a
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```
   Health endpoint: <http://localhost:8080/actuator/health>

## Testing
Run integration tests using Testcontainers:
```bash
./mvnw clean verify
```

## OpenAPI Docs
Swagger UI is available at <http://localhost:8080/swagger-ui/index.html> when running locally.
To export OpenAPI spec:
```bash
./scripts/export-openapi.sh
```
