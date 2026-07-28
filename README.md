# Xoboro

Xoboro is a fast, self-hosted media server built around an extensible library
model for comics, novels, books, video, and audio.

> [!IMPORTANT]
> Xoboro is under active development and is not ready to replace a production
> Komga installation yet.

## Relationship to Komga

Xoboro is derived from and inspired by
[Komga](https://github.com/gotson/komga). Komga 1.25.0 is the initial feature
reference: Xoboro aims to provide 99.9% of its useful server, library, reader,
metadata, administration, and interoperability capabilities without copying
its API shape, persistence model, implementation defects, or bottlenecks.

Komga is Copyright (c) 2019 Gauthier Roebroeck and is distributed under the
MIT License. Its license is preserved in
[`third-party/licenses/KOMGA.txt`](third-party/licenses/KOMGA.txt), and further
attribution is recorded in [`NOTICE`](NOTICE).

Xoboro is an independent project and is not affiliated with or endorsed by
the Komga project or its maintainers.

## Delivery order

1. Complete the UI-free Xoboro backend with Komga feature coverage, native
   APIs, migration tooling, standards-based protocol support, and automated
   verification.
2. Port the simple-komga reader experience and build a new administrator UI
   from a documented design system.
3. Profile and remove bottlenecks in scanning, analysis, task scheduling,
   metadata processing, and media delivery.

The future Android and iOS applications will live in a separate private
repository. Cross-platform domain code intended for those clients remains in
the public `core` modules in this repository.

## Repository layout

```text
core/
  application/       Portable use cases and external-service ports
  domain/            Platform-neutral domain model
server/
  api/               Versioned first-party HTTP API for web and native clients
  app/               Ktor application entry point
  media/             Media analysis and source materialization ports
  persistence/       SQLite migrations and jOOQ persistence adapters
  security/          Password hashing and authentication primitives
  sources/local/     Local filesystem source adapter
  tasks/             Leased durable worker runtime and task routing
compatibility/
  komga-api/         Transitional Komga-shaped HTTP adapters and protocol code
docs/                Architecture, feature coverage, testing, and design records
third-party/         Third-party license notices
```

UI modules are intentionally absent during the backend feature-completion
phase.

## Build and test

Requirements:

- [mise](https://mise.jdx.dev/) (installs the pinned Eclipse Temurin JDK 26)

Install the project toolchain, then run the complete verification suite:

```shell
mise install
mise exec -- ./gradlew check
```

Run the server:

```shell
mise exec -- ./gradlew :server:app:run
```

The initial health endpoint is available at `http://localhost:25600/health`.
Readiness is available at `http://localhost:25600/ready`.

The first-party API is rooted at `/api/xoboro/v1`. Its setup and session
contract, including cookie and bearer security requirements, is documented in
[`docs/api/native-v1.md`](docs/api/native-v1.md).

### Docker Compose

Copy the environment template, point `XOBORO_MEDIA_PATH` at an existing host
directory, and start Xoboro:

```shell
cp .env.example .env
docker compose up -d
docker compose ps
```

The first start builds the local multi-stage image. The SQLite database, fonts,
and generated state persist in the `xoboro-config` volume. Media is exposed
read-only at `/media`; create local libraries with a root below
`file:///media`. Rebuild source changes with:

```shell
docker compose up -d --build
```

Images built from every merged `main` revision are published for amd64 and
arm64 as `ghcr.io/xoboro/xoboro:latest` and `:main`. Set `XOBORO_IMAGE` to a
published tag to run it instead of the default local tag. Because the repository
is private, authenticate with a GitHub token that can read packages before
pulling.

Runtime defaults are safe for local development and can be overridden without
editing source:

| Environment variable | Default |
|---|---|
| `XOBORO_PORT` | `25600` |
| `XOBORO_DATABASE_PATH` | `./config/xoboro.sqlite` |
| `XOBORO_WORKER_COUNT` | `1` to `4`, based on available processors |
| `XOBORO_TASK_POLL_MILLIS` | `500` |
| `XOBORO_TASK_FAILURE_POLL_MILLIS` | `1000` |
| `XOBORO_TASK_LEASE_MILLIS` | `600000` |
| `XOBORO_SHUTDOWN_TIMEOUT_MILLIS` | `30000` |
| `XOBORO_CONTEXT_PATH` | unset |
| `KOMGA_CORS_ALLOWEDORIGINS` | unset |
| `XOBORO_TRUSTED_PROXIES` | unset |
| `XOBORO_METRICS_TOKEN` | unset |
| `KOMGA_OAUTH2_ACCOUNT_CREATION` | `false` |
| `KOMGA_OIDC_EMAIL_VERIFICATION` | `true` |

Malformed or out-of-range overrides fail startup instead of silently falling
back to another value.

Set `KOMGA_CORS_ALLOWEDORIGINS` to a comma-separated list of exact browser
origins, including scheme and port. Xoboro then matches Komga's credentialed
CORS behavior for REST, OPDS, SSE, OAuth, Kobo, and KOReader routes. Requests
carrying an unlisted `Origin` are rejected. Leave it unset when all browser
traffic is same-origin.

OAuth2 clients use Spring Security's environment naming, for example
`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID` and the matching
`..._CLIENT_SECRET`. Custom providers also set the corresponding
`SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_AUTHORIZATION_URI`, `TOKEN_URI`,
and `USER_INFO_URI`. For an OIDC provider, `ISSUER_URI` plus an `openid` scope
is sufficient: Xoboro validates and caches its standard
`/.well-known/openid-configuration` document. Explicit authorization, token,
user-info, and JWK-set URIs override discovered values. ID tokens may use
RS256/384/512 or ES256/384/512. Secrets are read at startup and are never
returned by an API.

Reverse-proxy headers are rejected by default. Set `XOBORO_TRUSTED_PROXIES`
to the comma-separated physical peer hosts or addresses that connect directly
to Xoboro. Only those peers may supply RFC `Forwarded` or `X-Forwarded-*`
headers; authenticated IPs, secure cookies, absolute links, and OAuth callback
URIs then use the verified origin. Do not add public client ranges.

Setting a random `XOBORO_METRICS_TOKEN` of at least 32 characters enables the
context-relative `/metrics` Prometheus endpoint. Scrape it with
`Authorization: Bearer <token>`. Metrics use bounded method and status-class
labels and expose request counts/durations, active requests, readiness, durable
queue size, worker count, and uptime. Request paths, catalog identifiers, user
data, and tokens are never labels or metric values.

At startup, Xoboro enqueues scans for libraries with `scanOnStartup` enabled
and registers each non-disabled `scanInterval`. Filesystem work runs through
the durable leased worker queue rather than on the scheduler thread.

### Database backup and restore

Create and verify a transactionally consistent backup while the server is
running:

```shell
mise exec -- ./gradlew :server:app:run --args="backup /srv/backup/xoboro.sqlite"
mise exec -- ./gradlew :server:app:run --args="verify-backup /srv/backup/xoboro.sqlite"
```

Restore only while the server is stopped. Replacing an existing database
requires explicit consent:

```shell
mise exec -- ./gradlew :server:app:run \
  --args="restore /srv/backup/xoboro.sqlite --replace"
```

The restore command rejects a running Xoboro process, validates the source and
staged copy, and removes stale SQLite WAL/SHM sidecars before startup.

### Importing a Komga 1.25 database

Stop both servers and create a verified backup before migration. Inspect a
Komga 1.25 database through a read-only SQLite connection:

```shell
mise exec -- ./gradlew :server:app:run \
  --args="import-komga /srv/komga/database.sqlite --dry-run"
```

Import into an empty Xoboro database, or explicitly replace all previously
imported state:

```shell
mise exec -- ./gradlew :server:app:run \
  --args="import-komga /srv/komga/database.sqlite"
mise exec -- ./gradlew :server:app:run \
  --args="import-komga /srv/komga/database.sqlite --replace"
```

The importer validates the exact Komga 1.25 schema and source integrity, then
migrates libraries, users and restrictions, catalog and metadata locks, media
indexes, progress, collections, read lists, embedded artwork, page-hash
policies, history, API keys, authentication activity, and client settings in
one Xoboro transaction. The source database is never written.

External artwork sidecars are deliberately rediscovered from media storage.
Komga server settings and Komga-specific synchronization snapshots are not
copied; Xoboro server settings retain their deployment defaults and each sync
adapter establishes new Xoboro snapshots after cutover.

## Feature status

The auditable release ledger is maintained in
[`docs/feature-coverage.md`](docs/feature-coverage.md). A feature is not
considered ready until its behavior, authorization, persistence, failure
handling, and automated tests have been verified. The former wire-compatibility
ledger remains in [`docs/komga-parity.md`](docs/komga-parity.md) as historical
implementation evidence, not as a product requirement.

## Test data policy

No personal library names, credentials, media, covers, or scraped metadata are
committed. Tests generate synthetic archives and metadata in temporary
directories. See [`docs/testing.md`](docs/testing.md).

## License

Xoboro is available under the [MIT License](LICENSE).
