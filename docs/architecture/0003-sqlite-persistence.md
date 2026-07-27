# ADR 0003: SQLite persistence with explicit migrations and transactions

- Status: accepted
- Date: 2026-07-27

## Context

Xoboro must preserve Komga's simple single-host deployment model while supporting a
large catalog, concurrent readers, crash-safe upgrades, and deterministic migrations.
The persistence layer must not leak into portable domain code.

## Decision

- Use SQLite as the embedded catalog database.
- Enable write-ahead logging, foreign-key enforcement, a bounded busy timeout, and
  `NORMAL` synchronous mode for every pooled connection.
- Version the schema with Flyway migrations. Application startup fails rather than
  running against a partially migrated or unknown schema.
- Use jOOQ's SQL DSL with explicit transaction boundaries. Do not introduce ORM entity
  lifecycle, lazy persistence proxies, or repository methods that hide expensive I/O.
- Keep filesystem and remote-source identifiers as URIs instead of assuming local
  filesystem paths.
- Start with one catalog database. Split queue/event workloads only when measurements
  demonstrate isolation is required.

## Consequences

SQLite keeps deployment and backup compatible with the appliance-style server target.
WAL permits readers during writes, but SQLite still has one writer at a time; write
transactions must therefore stay short and task concurrency must be bounded.
Schema changes require forward migrations and upgrade/restart tests.
