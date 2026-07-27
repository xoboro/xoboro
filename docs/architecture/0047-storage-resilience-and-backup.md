# ADR 0047: Storage resilience and verified database backup

## Status

Accepted.

## Context

A temporarily detached NAS mount must not look like an empty library. Treating
an unreadable root as a successful empty scan would soft-delete the catalog.
The SQLite database also needs a supported backup and restore path that remains
consistent while WAL writes are active.

## Decision

- Source adapters report an unreadable root with the shared
  `SourceInventoryUnavailableException` boundary.
- A failed root inventory aborts reconciliation before deletion and records the
  first unavailable timestamp on the library.
- A later successful inventory clears that timestamp. Both transitions publish
  the normal `LibraryChanged` event.
- Repeated failed scans preserve the first outage timestamp and do not emit
  duplicate availability events.
- Online backups use SQLite `VACUUM INTO`, validate `PRAGMA integrity_check`,
  flush the generated file, and move it into place atomically when supported.
- Restore is offline-only. It validates both the input and staged copy, rejects
  a running server through a process file lock, removes stale WAL/SHM sidecars,
  and atomically replaces the database file when supported.
- Backup, restore, and verification are explicit application commands. Restore
  never overwrites a database unless `--replace` is supplied.

## Consequences

A missing mount fails its durable scan task and follows the existing bounded
retry policy, while the prior catalog remains readable. Backup files contain a
compact, transactionally consistent SQLite image and can be opened independently
for disaster-recovery testing. Atomic rename fallback is available on filesystems
that do not expose atomic moves, but operators should keep backup and database
staging paths on local filesystems with reliable rename semantics.
