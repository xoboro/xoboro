# ADR 0010: Leased durable task execution

- Status: accepted
- Date: 2026-07-27

## Context

Catalog reconciliation persists expensive analysis work, but a queue entry alone does
not execute it. Multiple workers must be safe, long archive analysis must retain
ownership, failures must not spin, and a crashed process must leave work recoverable.

Task payloads are internal persisted contracts. Malformed or unknown payloads must not
be retried forever.

## Decision

- Route claimed tasks through one handler per non-blank task type.
- Claim with a unique lease token and run a periodic heartbeat at one third of the lease
  duration while the handler is active.
- Complete or fail a task only with the token that claimed it. If renewal, completion,
  or failure reports stale ownership, return `LeaseLost` and do not mutate it again.
- Retry handler failures with bounded exponential backoff. Stop retrying at the task's
  maximum attempt count.
- Dead-letter unknown task types immediately.
- Persist bounded non-empty failure text without serializing stack traces into the task
  table.
- Decode `ANALYZE_BOOK` as strict JSON containing a string `bookId`, then invoke the
  source-aware book analyzer.
- Keep `runOnce` deterministic and independently testable. The server lifecycle will
  own the repeated polling loop and heartbeat executor.

## Consequences

The complete local CBZ path now has an automated contract test from durable JSON task to
lease claim, media analysis, atomic persistence, and task completion. Failed handlers
back off and become dead letters, and expired ownership cannot commit a stale result.

Server startup/shutdown wiring, worker pool sizing, task metrics, and graceful drain
remain separate operational capabilities.
