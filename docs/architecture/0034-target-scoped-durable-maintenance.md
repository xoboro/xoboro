# ADR 0034: Target-scoped durable maintenance

## Context

Komga can request analysis and metadata refresh for a single book or an entire
series. Performing those operations inside an HTTP request would bypass the
bounded worker pool and would be lost when the server restarts. Queue clearing
must also avoid cancelling a task whose worker currently owns a lease.

These operations currently target the Comic compatibility adapter, but the
queue and requester contracts use durable work and media identities that can
later serve Novel, Book, Video, and Audio analyzers.

## Decision

- Add target-scoped analysis and metadata request methods that enqueue the same
  deduplicated task types used by library maintenance.
- Group book and series metadata work by series so one series is not mutated
  concurrently.
- Preserve Komga's missing-target semantics: book operations return not found;
  series operations accept an empty target set.
- Clear only pending and dead tasks. Never delete a running row with an active
  lease.
- Keep compatibility routes administrator-only and return immediately after
  durable emission.

## Consequences

Expensive work stays outside request threads and survives restart. Repeated
requests coalesce by stable task ID. Administrators can drain queued work
without invalidating worker ownership, while full cancellation and progress
reporting remain separate future capabilities.
