# Repository settings discovery

`GET /api/v1/repositories/{repositoryId}/settings-options` returns GitHub names for the repository settings dropdowns. It requires an active Manager membership in the repository's workspace. A different workspace receives 404; Leads receive 403.

The response has `branches`, `workflows`, and `environments`. Each contains `values` (names), `complete` (boolean), and an optional `warning`. Lists fail independently: a permissions error must not block editing settings manually. Empty successful lists are complete. Discovery never changes settings, tracking, or DORA data.

## GitHub access

Uses the existing GitHub App installation token and these read-only REST endpoints:

- [Branches](https://docs.github.com/en/rest/branches/branches#list-branches): Contents **read**.
- [Workflows](https://docs.github.com/en/rest/actions/workflows#list-repository-workflows): Actions **read**. Returns top-level workflow names, not jobs or steps.
- [Environments](https://docs.github.com/en/rest/deployments/environments#list-environments): Actions **read**. Returns configured GitHub environments, not cloud-provider resources.

No new environment variables, database migrations, Engine changes, or AWS configuration are needed. If the GitHub App lacks a permission, its owner must update its configuration and installation owners may need to approve it. Do not grant write permissions for discovery. No YAML is parsed or executed, and names are not evidence that a workflow actually deploys to production.

## Limits and caching

- Up to five pages of 100 entries per source, with explicit warnings for incomplete lists. Names are deduplicated and sorted.
- Discovery HTTP requests have a 2-second connect and 4-second read timeout. Token acquisition uses the existing token service/cache.
- Complete responses are cached for five minutes, up to 128 repository keys. Keys include workspace, repository, installation and owner/name. Repository access and installation status are checked before cache lookup. Partial/error responses are not cached.
- No database transaction is held during external discovery. Provider error bodies and tokens are never returned in warnings. The HTTP response is `no-store`; caching is internal to the application.
- Existing pattern validation is unchanged: 32 entries per field, 128 characters per entry. Frontend exact-name selections escape glob metacharacters; custom patterns remain globs.

## Verify locally

1. Run the API and companion frontend branches using your existing local GitHub integration configuration.
2. As Manager, open Integrations → a repository's Settings. Compare branch/workflow/environment options with GitHub. Switch signal type to see the relevant fields.
3. Select multiple names and add a custom pattern. Existing names not returned by GitHub must remain selected. Opening/searching does not save; only Save Settings does.
4. Test an empty repository or missing Actions/Contents read access: manual entry must remain available. Names newly created on GitHub can take five minutes to appear because of caching.
5. Run `./mvnw -Dtest=GithubRepositoryOptionsClientTest,RepositorySettingsOptionsServiceTest,GithubIntegrationIntegrationTest,OpenApiContractTest test` (Docker required for the integration/contract tests).

The API change can be released first; the previous frontend is unaffected. The new frontend also falls back to manual entry when this endpoint is not deployed yet.
