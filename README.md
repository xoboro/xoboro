# Xoboro

Xoboro is a fast, self-hosted media server built around an extensible library
model for comics, novels, books, video, and audio.

> [!IMPORTANT]
> Xoboro is under active development and is not ready to replace a production
> Komga installation yet.

## Relationship to Komga

Xoboro is derived from and inspired by
[Komga](https://github.com/gotson/komga). The first compatibility baseline is
Komga 1.25.0, and the project aims to reproduce 99.9% of its backend behavior,
API surface, media handling, metadata behavior, and supported protocols before
adding Xoboro-specific capabilities.

Komga is Copyright (c) 2019 Gauthier Roebroeck and is distributed under the
MIT License. Its license is preserved in
[`third-party/licenses/KOMGA.txt`](third-party/licenses/KOMGA.txt), and further
attribution is recorded in [`NOTICE`](NOTICE).

Xoboro is an independent project and is not affiliated with or endorsed by
the Komga project or its maintainers.

## Delivery order

1. Complete the UI-free Xoboro backend with Komga 1.25.0 compatibility,
   migration tooling, protocol support, and automated verification.
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
  app/               Ktor application entry point
  media/             Media analysis and source materialization ports
  persistence/       SQLite migrations and jOOQ persistence adapters
  security/          Password hashing and authentication primitives
  sources/local/     Local filesystem source adapter
  tasks/             Leased durable worker runtime and task routing
compatibility/
  komga-api/         Komga REST compatibility adapters and wire DTOs
docs/                Architecture, parity, testing, and design records
third-party/         Third-party license notices
```

UI modules are intentionally absent during the backend compatibility phase.

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
| `KOMGA_OAUTH2_ACCOUNT_CREATION` | `false` |
| `KOMGA_OIDC_EMAIL_VERIFICATION` | `true` |

Malformed or out-of-range overrides fail startup instead of silently falling
back to another value.

OAuth2 clients use Spring Security's environment naming, for example
`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID` and the matching
`..._CLIENT_SECRET`. Custom providers also set the corresponding
`SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_AUTHORIZATION_URI`, `TOKEN_URI`,
and `USER_INFO_URI`; OIDC providers additionally set `ISSUER_URI` and
`JWK_SET_URI`. Secrets are read at startup and are never returned by an API.

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

## Compatibility status

The auditable compatibility ledger is maintained in
[`docs/komga-parity.md`](docs/komga-parity.md). A feature is not considered
compatible until behavior, authorization, persistence, error handling, and
automated tests have all been verified.

## Test data policy

No personal library names, credentials, media, covers, or scraped metadata are
committed. Tests generate synthetic archives and metadata in temporary
directories. See [`docs/testing.md`](docs/testing.md).

## License

Xoboro is available under the [MIT License](LICENSE).
