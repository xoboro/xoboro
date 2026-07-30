# 0094 — Putting grouping membership on the artwork event

## Status

Accepted. Closes the gap recorded in ADR 0088 and the seventh entry of the wiki's known-defect list.

## Context

`poster.changed` was not emitted at all when the artwork's owner was a collection or a read list.
`XoboroNativeEventBridge.map(ArtworkEvent)` returned `null` for both owner kinds.

The reason was sound and is worth restating, because it constrains the fix. A subscriber may only
learn that a grouping's cover changed if they can see at least one of its members —
`XoboroNativeEventScope.SeriesMembers` and `MediaItemMembers` already encode that rule. But
`ArtworkEvent` carried only `owner.kind` and `owner.id`, so the bridge had exactly two options, both
bad:

- **Query the grouping.** That puts a repository read inside an event mapper. The read races the change
  it describes, and for a deletion it can no longer find the grouping at all — precisely when the event
  matters most.
- **Invent a scope.** Broadcasting, or scoping to something broader than the membership, looks correct
  and is not. ADR 0087 had already declined a fail-open filter for the same reason.

So the event was dropped, and a client showing a collection cover showed a stale one until it refetched
for an unrelated reason. ADR 0088 recorded that honestly rather than closing it with a guess.

The gap became more visible after collections and read lists gained *derived* covers (#135): they now
have artwork that actually changes, so the missing event went from theoretical to routine.

## Decision

The membership moves onto the domain event as `ArtworkEvent.groupingMembers`, filled by
`ArtworkLifecycle` from an injected `ArtworkGroupingMembers` resolver.

This keeps the bridge a **pure function of what it was handed** — the property that made it testable
without a database, and the property that dropping the event was protecting. The resolver runs where a
repository read is already ordinary (the lifecycle, inside the mutation that caused the change), not in
the mapper. It also makes the event resolvable *after* the grouping is deleted, which is the case a
lookup could never have handled.

Members are `List<String>` with the type implied by `owner.kind` — series ids for a collection, media
item ids for a read list. A typed union was rejected: `core/application` would have had to define a
second scope vocabulary that duplicates `XoboroNativeEventScope`'s shape while serving one consumer,
and the owner kind already determines the interpretation unambiguously.

`ArtworkGroupingMembers.NONE` is the default and resolves nothing, which reproduces the previous
behaviour exactly. A construction that does not wire a resolver keeps grouping events unannounced
rather than announcing them with an empty — and therefore invisible — scope.

The resolver is called **once per lifecycle call**, not once per published event. `replaceGenerated`
and `replaceSidecars` publish one `Deleted` per superseded item plus one or more `Added`, and the
membership is identical for all of them.

### An empty grouping is still not announced

If a grouping has no members, `groupingMembers` is empty and the bridge returns `null`. That is not a
leftover of the old behaviour but the correct answer: `SeriesMembers` defines "no visible members" as
not visible, so there is no subscriber entitled to the event, and emitting it would require a scope
broader than the truth. A client that creates an empty collection, uploads a cover, and waits for an
event will wait forever — it must re-read after its own mutation. `docs/api/native-v1.md` says so.

### The Komga-compatible stream is deliberately not fixed

`KomgaSseEventHub` filters on `libraryId` only. Turning a member list into libraries would put back
exactly the per-event lookup the membership was added to remove, and adding a member-based filter means
extending a surface that is frozen by ADR 0082 for no client that asks for it. Komga itself broadcasts
these events. The gap stays recorded in ADR 0087; the compat bridge's KDoc now states that the
membership is available and why it still is not used.

## Consequences

- `poster.changed` is emitted for all four owner kinds, scoped by membership.
- The `ids` payload names the grouping, not its members. The client is being told which cover changed;
  the membership is only how the server decided who may hear it. A test asserts this, because emitting
  member ids here would silently change what the payload means.
- `ArtworkLifecycle` gains one optional constructor parameter and no new repository dependency.
- Three tests replace the one that pinned the gap: grouping owners map with their members, and an
  empty grouping still maps to `null`. That last assertion is kept deliberately — it is what stops a
  future fail-open fallback from slipping in as a "fix" for the empty case.
