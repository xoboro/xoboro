# 0091 — Extracting the SSE authorization gate

## Status

Accepted.

## Context

The Komga-compat SSE route re-resolves an open stream's subscriber periodically and closes the stream
once the subscriber's authorization changed or the account was deleted. Two properties make that rule
hold:

- **Checked on every wake, not only on the task-poll timeout.** A stream busy with catalog events
  never times out, so a check confined to the timeout branch would keep a revoked subscriber live for
  as long as events keep arriving.
- **Rate-limited to one lookup per recheck period per subscriber.** The snapshot is a database read;
  without a deadline an event-heavy stream would issue one query per event.

Both lived inline in the route's `while (true)` loop, and both were **uncovered**. Two tests that
tried to cover them through `testApplication` were quarantined with `@Disabled` (#117) after five CI
rounds: every shape attempted — collecting the stream to completion, reading the raw channel,
requiring non-delivery, polling the hub subscription count — left the server-side SSE coroutine alive
under CI load, which `runTest` reports as `UncompletedCoroutinesError` instead of as the assertion the
test meant to make. Those two tests were the project's only skipped tests.

Quarantining was the right call at the time — a test that reports a coroutine-lifecycle error instead
of a verdict is worse than no test, because it trains the reader to ignore it. But it left a security
behaviour with no regression coverage, and it left the count at "2 skipped" indefinitely.

## Decision

The decision logic moves into `KomgaSseAuthorizationGate`, a class with no coroutine of its own and no
transport. It takes the connected `User`, the recheck period, a `TimeSource`, and a suspend function
that reads the current snapshot. Its single method, `admitsAnotherEvent()`, returns false once the
subscriber must be cut off.

The route calls it **unconditionally, once per wake, before deciding what to send**. The gate has no
notion of *why* it was woken, so the route cannot reintroduce the timeout-branch-only bug by accident:
there is no branch left to put the check inside.

The `TimeSource` parameter is what makes the rate limit testable. `TestTimeSource` advances on demand,
so "does not query before the period elapses" and "queries once per period however often it is woken"
are ordinary assertions rather than sleeps.

The two quarantined tests are deleted. Their rule is now covered by six tests that run, and keeping a
disabled duplicate would only re-assert that the HTTP shape is unobservable — something recorded here
and in the wiki rather than in a test that never executes.

The deadline is re-armed from the moment the check *completed*, not from the moment it came due. Under
a slow lookup the second form would leave the next check immediately overdue, silently degrading the
rate limit into a per-event query.

## Consequences

- The project has **no skipped tests**. The rule the skipped ones covered is now asserted
  deterministically, and each assertion was mutation-checked: dropping the rate limit, ignoring an
  authorization change, and ignoring a deleted account each fail exactly the tests that name that
  behaviour.
- What is **not** covered by a test is the route's single call site — that the route calls
  `admitsAnotherEvent()` at all, and before sending. That is verified by inspection. An HTTP test of
  it is precisely the shape that was quarantined, and the structural change (one unconditional call,
  no branch to hide in) is what replaces the coverage. This is a real, named residual, not a claim of
  completeness.
- `KomgaSseUserSnapshot` remains a required, non-defaulted parameter of `komgaSseRoutes`, so a call
  site still cannot opt out of revocation.

## Status of the nine known defects recorded in the wiki

The wiki's defect list was written at `main` = `8df87f7` (#118) and had gone stale. Verified against
`main` = `64ad844` (#136):

| # | Defect | State |
|---|---|---|
| 1 | Global `StatusPages` flattened native 404/403 codes | Fixed — routes mark `XoboroNativeErrorBodyWritten` and the handler returns early |
| 2 | One-shot `seriesTitle` was the file name | Fixed — #123 re-refreshes one-shot series metadata after its book updates |
| 3 | `countsByType` counted `DEAD` as queued work | Fixed — `WHERE state <> 'DEAD'` |
| 4 | No trace when a task died | Fixed — `DurableTaskWorker` writes a dead-letter log line |
| 5 | `clearUnclaimed` also deleted `PENDING` | Fixed — `clearPending` added as the native counterpart |
| 6 | Two quarantined tests left the SSE recheck uncovered | **Fixed by this ADR** |
| 7 | `poster.changed` is not emitted for collection and read-list artwork owners | Open, by design for now — `ArtworkEvent` does not carry the grouping's membership, so the bridge cannot scope the event without a repository lookup of its own. ADR 0088 records it. Fixing it means putting the scope on the domain event, which belongs with the remaining native-event work |
| 8 | Compat SSE broadcasts organization and artwork events unscoped | Open, recorded in ADR 0087 |
| 9 | `SQLITE_BUSY_SNAPSHOT` race on series lookup | Fixed — `forceImmediateWriteLock`/`retryOnBusySnapshot` with a concurrency regression test |

Six were already fixed and the list had not been updated, which is its own lesson: a defect list that
is not re-verified becomes a source of false work.
