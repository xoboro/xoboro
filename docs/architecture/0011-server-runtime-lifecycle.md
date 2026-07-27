# ADR 0011: Explicit server runtime lifecycle

- Status: accepted
- Date: 2026-07-27

## Context

Repositories, analyzers, and a deterministic worker are not sufficient unless the real
server process composes and owns them. Configuration must work in local and container
deployments, invalid values must fail visibly, and shutdown must not close SQLite while
workers are still using it.

Liveness and readiness also have different meanings: an HTTP process may be alive while
its database is unavailable.

## Decision

- Parse all runtime overrides once into an immutable `ServerConfig`.
- Resolve a relative database path against the process working directory. Do not embed
  machine-specific paths, hosts, credentials, or library data.
- Reject malformed and out-of-range values rather than silently applying defaults.
- Size the SQLite pool from the configured worker count with a bounded maximum.
- Compose local source access, repositories, ZIP analysis, strict task handlers, leased
  workers, and the worker pool in one `XoboroRuntime`.
- Poll idle and failed workers with separate bounded delays; never busy-spin.
- Start a fixed worker pool after all resources are constructed.
- Subscribe runtime closure to Ktor's `ApplicationStopped` event. Stop and drain workers
  before closing heartbeat scheduling and SQLite; repeated close calls are safe.
- Keep `/health` as liveness and expose database-backed `/ready` readiness.

## Consequences

Running the Ktor entry point now creates an upgrade-safe SQLite database and actively
processes durable analysis work. Relative defaults work for development and Docker
volumes, while production can set every operational path and timing through `XOBORO_*`.

HTTP library management, metrics, and graceful in-flight task reporting remain later
server capabilities. Startup and periodic scan scheduling are specified separately in
ADR 0012.
