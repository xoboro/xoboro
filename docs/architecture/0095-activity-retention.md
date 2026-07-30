# 0095 — Activity retention

## Status

Accepted.

## Context

Two coverage rows asked for retention controls: `Historical activity` ("add retention controls") and
`Authentication activity` ("schedule retention cleanup").

The starting state was lopsided. `AuthenticationActivityRepository.deleteOlderThan` existed and **nobody
called it** — a delete path with no caller, which is worse than no delete path, because it reads as a
feature. `HistoricalEventRepository` had no delete path at all. So both tables grew without bound, and
history grows fastest on exactly the deployments that scan the most.

## Decision

### Zero means keep forever, and is the default

`ActivityRetention` carries two day windows. `0` keeps everything, and both default to it.

Defaulting to any positive window would silently discard audit rows on upgrade. An operator who has not
chosen a retention policy has not asked for their history to be pruned, and retention is the one setting
where the safe default is the one that does nothing. Turning it on is a deliberate act.

Days, not hours or millis, because a day is the unit an operator reasons in and because it is coarse
enough that the sweep cadence can never be what decides whether a row survives.

### The two windows are independent

Authentication activity and catalog history answer different questions — "who tried to get in" versus
"what happened to the library". An operator who wants a short security-log window rarely wants a year of
catalog history to go with it. One combined window would have forced that trade.

### The cutoff is absolute, and computed by the caller's clock

`deleteOlderThan` takes an instant, not an age. The sweep is then a pure function of the value it is
handed, which is what makes it testable without waiting, and it keeps the "what is now" decision in one
place instead of inside two repositories.

`ActivityRetentionLifecycle.cutoff` returns **null** rather than a cutoff for three cases, and each is a
deliberate refusal to issue a statement:

- **Keep forever.** A delete guaranteed to match nothing still takes a write lock on every sweep. "Keep
  forever" must cost no writes, and a test asserts no statement is issued at all rather than asserting
  that nothing was deleted.
- **Window reaches past the epoch.** On a fresh install with a large window the cutoff would be
  negative. A negative cutoff is a delete that means nothing.
- **Window overflows.** `days * MILLIS_PER_DAY` can wrap for an absurd window, and a wrapped value
  would delete *far more* than asked — the failure mode points the wrong way, so it is detected.

Deletion is **strictly older than** the cutoff, so a window of N days keeps rows exactly N days old.
A persistence test pins the boundary row, because an off-by-one here silently shortens every window by a
day.

### The policy is read per sweep

`ActivityRetentionLifecycle` takes `retention: () -> ActivityRetention`, not a value. A change through
the settings API takes effect at the next sweep rather than at the next restart. A test asserts that by
changing the policy between two sweeps.

### The sweep is scheduled, not queued

`ActivityRetentionScheduler` reuses the existing `FixedRateTaskScheduler` and does not go through the
durable task queue. A sweep is idempotent, cheap, and carries no result anyone waits on — durability
would buy nothing while adding a row per sweep to the very kind of table this exists to keep small.

The interval is six hours. Retention windows are whole days, so any interval well under a day makes the
cadence invisible in the outcome; six hours keeps a deployment that *shortens* its window from waiting
most of a day to see it take effect.

The first sweep is delayed by one interval rather than running at startup. Sweeping during startup would
compete for the write lock with the scan and analysis work a restart kicks off, and nothing about
retention is urgent enough to justify that.

### History property rows cascade

`historical_event_property` has `ON DELETE CASCADE`, so the sweep deletes only parents. An orphaned
property row would be reattached to whatever event later reused the id; a test asserts the cascade
rather than trusting the schema comment.

## Consequences

- Both windows are exposed on `GET`/`PUT /api/xoboro/v1/server-settings` and validated as non-negative.
  A negative value is rejected rather than becoming a cutoff in the future that would delete everything.
- Retention applies when a sweep runs, not when a row is read. Shortening a window does not hide rows
  that are still stored, and lengthening one does not bring back rows already deleted.
- `docs/api/native-v1.md` now also states, per setting, when a change takes effect — the restart-required
  documentation the `Runtime configuration` row asked for. The API deliberately reports no derived
  "restart required" flag: for the three multi-source settings, `databaseSource` differing from
  `effectiveValue` already says it, and a flag could disagree with the values it was derived from.
- Recording session and OAuth flows as authentication activity is **not** in this change. It is a
  separate question — what gets written — from how long writes are kept, and the coverage rows still
  say it is missing.
