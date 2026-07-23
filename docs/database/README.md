# Adept database diagram

Flyway migrations in `src/main/resources/db/migration` are the only schema source of truth.

Generate an HTML schema report from the current local PostgreSQL database:

```bash
./scripts/generate-erd.sh
```

Open `docs/database/erd/index.html` in a browser. The generated directory is ignored because it can always be rebuilt. The root `Adept-Complete-Database-Schema.sql` file is a convenient combined snapshot for manual ERD clients; the application itself must continue to use Flyway V1–V7.
