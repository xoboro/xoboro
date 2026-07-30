# ADR 0088: Native event stream

- Status: Accepted
- Date: 2026-07-29

## Context

ADR 0086 defines the native catalog surface and ADR 0087 hardens the
Komga-compatible SSE stream's subscriber authorization, but neither wires a
native equivalent. `XoboroNativeEventHub`, `XoboroNativeEventBridge`, and
`XoboroNativeEventScope`/visibility filtering (added ahead of this decision)
already define the event shape, the per-subscriber filtering contract, and
the resume/backpressure/limit behaviour; `xoboroNativeEventRoutes` already
defines the `GET /api/xoboro/v1/events` route. What remained was mounting
the route, constructing the hub inside `XoboroRuntime`, and fanning every
domain-event publish site out to it alongside the existing Komga bridge.

Four design questions were open at that point, and this ADR records the
answers, most of which were already implied by the merged code and are
restated here so the decision is traceable to one place rather than only to
class-doc comments scattered across several files.

## Decision

**Per-subscriber filtering at emit time, not at connect time.** Every
`publish` call decides visibility against each live subscriber's *current*
`User` row and library grants, and every buffered replay re-decides
visibility against the *reconnecting* subscriber's current access rather
than whatever was true when the event was buffered. ADR 0087 already
establishes that authorization can change mid-connection for the
Komga-compatible stream; the native stream applies the same principle to
every filtering decision, live or replayed, rather than baking a decision in
once.

**Payloads carry identifiers, never entity snapshots.** A snapshot embeds
the visibility decision made at write time, and that decision can be wrong
by the time a slow consumer or a resumed replay actually delivers it — an
authorization change in between turns a once-authorized snapshot into a
disclosure with no point at which it could have been caught. An identifier
forces the client back through the regular, currently-authorizing read
route, so exactly one code path ever decides "can this caller see this."
This is why a client that receives a batch of `ids` must invalidate and
re-query rather than trust the event as a change log.

**Resume with an explicit gap signal, never a silent truncation.** A
reconnecting client's `Last-Event-ID` either resumes cleanly (with replay,
if still buffered) or receives `stream.resync-required {reason: "gap"}` — it
is never given a shorter, silently incomplete history and told it is
current. The sequence counter that makes this possible counts publications,
not deliveries, so a filtered subscriber legitimately observes gaps in the
numbers it receives; only the explicit resync frame means loss.

**Resync-over-silent-drop for a slow consumer, and eviction-over-rejection
for a busy user.** A subscriber whose per-connection queue fills is not fed
a truncated tail of events; its queue is discarded and the next `receive()`
announces `stream.resync-required {reason: "overflow"}` instead. A user
opening a fifth stream is not refused — the oldest of their existing streams
is closed with `reason: "superseded"` — because rejecting the newest
connection would strand the user behind one the server has not yet noticed
is half-dead (a backgrounded tab, for instance), while closing the oldest
guarantees the user ends up with their newest intent honoured.

**The deliberate drops stay drops.** `XoboroNativeEventBridge.map` returns
`null` for `CatalogImportEvent` (there is no native import endpoint, and its
`sourceFile` is an absolute server path) and for `UserEvent.SessionsExpired`
(the stream already re-authenticates itself every recheck period against the
same fields that event would announce, making the event redundant rather
than informative). Every publish site in `XoboroRuntime` is routed through
the bridge — including the two sites above — so each drop is a decision made
once inside the bridge, not a second, silently-diverging skip repeated at
every call site. The one exception is the `CatalogImportEvent` publish site
itself (`CatalogSourceFileLifecycle.importEventPublisher`), which is left
publishing only to the Komga bridge with an explicit comment, since routing
a value through a mapping function that is guaranteed to discard it adds a
call for no observable effect.

**`ArtworkEvent` for a collection or read-list owner also maps to `null`,**
for a different reason: those owner kinds carry no member series/media-item
identifiers on the event itself, and resolving them would mean the bridge
performing its own repository lookup, then guessing at a scope broader than
true (or none at all) when that lookup can no longer find the collection.
The gap is the same shape as ADR 0087's for the Komga stream: `poster.changed`
is simply not emitted for those two owner kinds, and a client showing a
collection or read-list cover shows a stale one until it refetches for an
unrelated reason.

## Consequences

A native client can now open one stream and receive scoped catalog, series,
media-item, collection, read-list, read/series-progress, and poster-change
notifications, with a documented, testable freshness guarantee: having seen
`stream.ready {resumed: true}` since the last `stream.resync-required` means
nothing entitled to the subscriber has been missed.

The native stream does not inherit the Komga-compatible stream's broadcast
gap for `OrganizationEvent`/`ArtworkEvent` recorded in ADR 0087 — collections
and read lists are scoped to their visible members here — but it introduces
its own, narrower gap for the two artwork owner kinds that cannot be scoped
without a lookup, and for the two event families the bridge intentionally
discards. These are recorded here rather than closed silently, matching
ADR 0087's own directive to record deliberate reductions instead of treating
"broadcast a little more than we should" and "say nothing at all" as
equivalent.

Fanning out every publish site individually (rather than, say, wrapping
`KomgaSseEventBridge` itself) keeps the native mapping decision colocated
with each event's construction and visible in a diff, at the cost of one
extra lambda per publish site in `XoboroRuntime`; a future refactor that
consolidates "publish to both bridges" into one helper remains possible
without changing this decision.
