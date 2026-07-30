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
| `XOBORO_OAUTH2_ACCOUNT_LINKING` | `VERIFIED_EMAIL` |

Malformed or out-of-range overrides fail startup instead of silently falling
back to another value.

`XOBORO_OAUTH2_ACCOUNT_LINKING` decides whether an external OAuth2/OIDC identity
may sign in as an **existing** local account whose email it matches:

| Value | Behaviour |
| --- | --- |
| `VERIFIED_EMAIL` | Default. Links only when the provider asserted it verified the email. A plain OAuth2 provider makes no such assertion, so it can never link — only create a new account, and only if `KOMGA_OAUTH2_ACCOUNT_CREATION` allows it. |
| `EMAIL` | Links on an email match whatever the provider verified. This is Komga's behaviour. |
| `NEVER` | Never links. A matching email is refused rather than signed in or duplicated. |

The default is the safe value rather than Komga's, because with a plain OAuth2
provider — or OIDC with `KOMGA_OIDC_EMAIL_VERIFICATION=false` — `EMAIL` means
whoever can register an address at the provider can enter the matching local
account. Set `EMAIL` explicitly if you are migrating a deployment that relied on
it. Administrators can read the resolved policy back from
`GET /api/xoboro/v1/authentication/oauth2`; see ADR 0093.

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

### Performance harness

An opt-in harness generates a synthetic large library on disk (deterministic,
seeded, no real titles or media) and drives it through the production scanner
and analyzer, then measures native API latency against the resulting
database. It is not part of `check` or `test`; run it explicitly:

```shell
mise exec -- ./gradlew :server:app:performanceHarness \
  -Pxoboro.perf.seriesCount=300 \
  -Pxoboro.perf.booksPerSeries=10 \
  -Pxoboro.perf.oneShotCount=50
```

Omit the properties for a small, fast smoke run (20 series × 5 books + 5
one-shots).

The harness waits for the durable task queue to drain before it measures API
latency, and reports that wait as `queue_drain.*`. This matters more than it
sounds: opening a runtime on a 15,050-item library enqueues over ten thousand
background tasks and takes about four and a half minutes to clear, and every
latency number measured during that is meaningless. A run that fails to drain
prints a warning with the pending count by task type and is not comparable with
one that drained.

`scripts/cold-scan-repetitions.sh` repeats the cold-scan metrics in separate JVMs
and prints a CSV of the spread. Cold metrics cannot be repeated in-process — the
second iteration is JIT-warm and no longer measuring a cold start. Output is printed twice: stable `xoboro.perf.<metric>=<value>`
lines for diffing across runs, and a markdown table. It reports numbers only
and does not assert performance thresholds; see the harness class doc at
`server/app/src/test/kotlin/io/xoboro/server/perf/PerformanceHarnessTest.kt`
for exactly what it measures and what it deliberately does not (concurrent
multi-user load, a cold OS page cache, real network transport, artwork
generation).

One metric, `api.first_series_read_after_scan`, is a single cold-cache
observation of the first `/series` call after a scan, not steady-state
latency: that call absorbs a one-time full-catalog metadata-aggregation
rebuild covering every series scanned so far, and can be considerably
slower than the `api.series_listing` steady-state numbers reported
alongside it. Do not quote it as `/series` performance. It is reported
as four separate values rather than one: `.successful_attempt` (the
isolated duration of the call that actually succeeded), `.harness_wall`
(the retry loop's full wall-clock time including every failed attempt
and backoff sleep — this is harness time, not a server cost, and is
labeled as such), `.attempts`/`.failed_attempts`, and
`.last_failure_type`. A single number that folded backoff sleep into a
"cold read" latency would measure the harness's own retry loop instead
of the server.

`unchanged_rescan.wall` reruns the rescan several times against the
already-open, already-warm runtime and reports `.p50`/`.min`/`.max`
across those reps, the same shape used for the API latency metrics.
This is deliberately different from `cold_scan`/`cold_analyze`/
`cold_full_scan`, which are reported from a single sample each — see
below for why.

#### A single run cannot establish a regression or an improvement

Three runs of `cold_analyze` at the same size (300 series × 10 books +
50 one-shots) on the same machine came back as 9,385 / 12,738 / 11,079
ms; `cold_full_scan` came back as 12,436 / 16,058 / 14,223 ms. That is
roughly ±25% run-to-run variance. If you run this harness once before a
change and once after, and the two numbers differ by 20%, that
difference is noise, not a verdict — the variance above is bigger than
most regressions or improvements you would be checking for.

To get a number that can actually support a before/after comparison,
run the harness multiple times as **separate Gradle invocations** and
compare medians across runs, not single numbers:

```shell
for i in $(seq 1 5); do
  mise exec -- ./gradlew :server:app:performanceHarness \
    -Pxoboro.perf.seriesCount=300 \
    -Pxoboro.perf.booksPerSeries=10 \
    -Pxoboro.perf.oneShotCount=50
done
```

then take the median of the `xoboro.perf.cold_*` lines across the five
runs. This has to be separate process invocations, not a loop added
inside the harness: `cold_scan`/`cold_analyze`/`cold_full_scan` measure
the first request of a JVM's lifetime, before JIT compilation and class
loading have caught up. Looping that sequence inside one JVM would make
later reps systematically faster — a warming trend, not noise — and a
median or trimmed mean over those reps would quietly stop measuring a
cold start. `unchanged_rescan` does not have this problem, which is why
it is the one metric repeated in-process (see above): a real deployment
reruns it warm, every `scanInterval`, so back-to-back in-process calls
measure the real, repeatable thing.

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
