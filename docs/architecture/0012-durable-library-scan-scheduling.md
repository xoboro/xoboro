# ADR 0012: Durable library scan scheduling

- Status: accepted
- Date: 2026-07-27

## Context

Catalog reconciliation was callable in tests but the production runtime could not
execute a persisted scan request. Libraries configured for startup or periodic scans
also remained idle. Scheduling filesystem work directly would lose requests during a
restart and bypass the leased worker guarantees.

Komga 1.25.0 uses a stable scan identity containing the library and deep-scan flag,
enqueues startup scans after the application is ready, and schedules enabled intervals
at a fixed rate with the first run delayed by one full interval.

## Decision

- Persist `SCAN_LIBRARY` tasks with a stable
  `SCAN_LIBRARY_<libraryId>_DEEP_<boolean>` identity and strict JSON payload.
- Keep normal and deep scans independently deduplicated and preserve the caller's
  priority.
- Resolve the library when the task executes. A request for a library deleted after
  emission completes as a no-op instead of retrying.
- Route valid tasks through the source-aware `CatalogScanner`; reconciliation persists
  all resulting series, books, deletions, and follow-up analysis tasks atomically.
- On runtime startup, enqueue every library with `scanOnStartup` enabled.
- Schedule all non-disabled intervals at fixed rate with initial delay equal to the
  interval. Rescheduling replaces the old registration, and disabling cancels it.
- Catch scheduled callback failures so one transient queue failure cannot silently
  disable all later runs.
- Stop scan scheduling before draining workers and closing SQLite.

## Consequences

Starting Xoboro now activates the complete local path from configured library scan to
catalog reconciliation and durable media analysis. Scan requests survive process
failure once enqueued, and periodic callbacks only perform the small queue write rather
than filesystem work on the scheduler thread.

Library CRUD REST wiring will call the same emitter and reschedule methods. Unavailable
storage policy, trash processing, remaining maintenance tasks, and scan events remain
separate compatibility increments.
