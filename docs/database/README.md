# Adept database diagram

Flyway migrations in `src/main/resources/db/migration` are the only schema source of truth.

Generate an HTML schema report from the current local PostgreSQL database:
```bash
./scripts/generate-erd.sh
