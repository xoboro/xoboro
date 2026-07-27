# ADR 0005: Leased durable task queue

- Status: accepted
- Date: 2026-07-27

## Context

Komga persists prioritized tasks and resets all owned tasks during startup. A crashed or
stalled worker has no bounded ownership lease, failures are not represented as retry or
dead-letter states, and safe multi-process claiming is not an explicit contract. Xoboro
must retain priority and group serialization while making recovery deterministic.

## Decision

- Persist pending, running, and dead tasks in SQLite.
- Preserve Komga's priority scale from 0 through 8 and select equal-priority work FIFO.
- Use task IDs as deduplication keys. Pending duplicates update their payload and
  priority; running tasks are immutable.
- Claim work with an atomic SQLite `UPDATE ... RETURNING` statement.
- Give every claim an owner, opaque token, and expiration timestamp.
- Allow at most one live task per non-null group.
- Reclaim expired leases while attempts remain, then dead-letter exhausted work.
- Require the current lease token for renewal, completion, and failure.
- Store retry availability separately so backoff does not occupy a worker.

## Consequences

Worker death no longer requires resetting every running task at startup. Multiple workers
cannot successfully claim the same row, and stale workers cannot complete work claimed
by a replacement. Dead tasks remain observable for administrator recovery tooling.
Task payload codecs, handlers, worker supervision, and administration endpoints are
separate follow-up capabilities.
