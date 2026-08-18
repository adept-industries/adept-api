# Development GitHub Importer

The local-only importer loads real public GitHub history into Adept's existing
tables. It is intended for development demos and QA data, not production.

It does not create migrations or demo-specific tables. It creates/reuses one
demo workspace, one Manager account, two Lead accounts, a project, a development
GitHub integration row, tracked repository rows, pull request rows, pull request
feature rows, optional GitHub Actions workflow rows as deployments, Lead
assignments, and one recalculation job.

## Run

Start PostgreSQL first:

```bash
docker compose --env-file ../.env \
  -f infra/local/compose.yaml \
  up -d postgres
```

From `adept-api`:

```bash
scripts/import-github-repo tiangolo/fastapi --max-prs 100
```

For larger imports, set a GitHub token to avoid the anonymous API rate limit:

```bash
export GITHUB_TOKEN=ghp_your_token_here
scripts/import-github-repo tiangolo/fastapi --max-prs 500 --max-workflow-runs 100
```

The token needs only public read access for public repositories. Fine-grained
tokens can be limited to public repository metadata, pull requests, contents,
issues/comments, and actions.

Anonymous GitHub imports can fail quickly with a `403` rate-limit response. Set
`GITHUB_TOKEN` before importing useful PR counts.

## Analytics

The importer writes normalized repository, pull request, feature, and optional
deployment rows, then queues one existing `RECALCULATE_METRICS` processing job.
It does not create `metric_snapshots` or `risk_predictions` directly. In this
checkout, the Python worker's job dispatcher still has placeholder handlers for
GitHub backfill/sync jobs and no real `RECALCULATE_METRICS` handler yet, so the
historical data is ready for analytics but metrics are not fabricated by the
importer.

## Demo Login

Defaults:

```text
Manager: demo.manager@adept.local
Lead: demo.lead@adept.local
Co-Lead: demo.colead@adept.local
Password: AdeptDemoPass123!
Workspace slug: github-demo-data
```

Override with:

```bash
export ADEPT_DEMO_PASSWORD='YourLocalDemoPassword123!'
export ADEPT_DEMO_MANAGER_EMAIL=manager@example.test
export ADEPT_DEMO_LEAD_EMAIL=lead@example.test
export ADEPT_DEMO_COLEAD_EMAIL=colead@example.test
export ADEPT_DEMO_WORKSPACE_NAME='GitHub Demo Data'
export ADEPT_DEMO_WORKSPACE_SLUG=github-demo-data
```

After importing, start the API/frontend normally and sign in as the Manager or
Lead. The Manager sees the imported tracked repositories. The Lead accounts see
only repositories assigned to their workspace memberships.

## Cleanup

From `adept-api`:

```bash
scripts/import-github-repo --remove-demo-data
```

This removes the dedicated demo workspace and its cascaded repository, PR,
feature, deployment, project, assignment, and job rows. Demo users are removed
only when they have no remaining memberships.

## DBeaver

Use a PostgreSQL connection:

```text
Host: localhost
Port: value of POSTGRES_PORT in ../.env, usually 5432
Database: value of POSTGRES_DB in ../.env, usually adept
Username: value of POSTGRES_USER in ../.env, usually adept
Password: value of POSTGRES_PASSWORD in ../.env
```

Useful checks:

```sql
select email, display_name, email_verified_at from users order by email;

select w.name, w.slug, m.role, u.email
from memberships m
join users u on u.id = m.user_id
join workspaces w on w.id = m.workspace_id
where w.slug = 'github-demo-data'
order by m.role, u.email;

select r.full_name, r.tracking_enabled, count(pr.id) as pull_requests
from repositories r
left join pull_requests pr on pr.repository_id = r.id
join workspaces w on w.id = r.workspace_id
where w.slug = 'github-demo-data'
group by r.id, r.full_name, r.tracking_enabled
order by r.full_name;

select pr.number, pr.title, pr.state, pr.author_login, pr.opened_at, pr.merged_at
from pull_requests pr
join repositories r on r.id = pr.repository_id
join workspaces w on w.id = pr.workspace_id
where w.slug = 'github-demo-data'
order by pr.opened_at desc
limit 50;
```
