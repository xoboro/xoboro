# 0103 — Named discovery feeds

## Status

Accepted.

## Context

The coverage row asked for "named discovery feeds". Everything they return was already reachable:
`sort`, `onDeck` and `keepReading` are parameters on the existing listings, so any client could build
every feed today.

Which raises the obvious objection — why add routes that add no capability?

## Decision

### The feed owns its ordering, and that is the whole point

When each client decides what "latest" sorts by, two clients showing a shelf with the same label show
**different shelves**. Worse, a report that "latest is wrong" cannot be answered, because nothing ever
said what right was. The feed exists so that the meaning lives on the server, once.

`new` sorts by `createdAt`, not by the file's own timestamp: "new to me" is what a reader means, and a
decade-old file copied in today is new to *this* library. `updated` is deliberately distinct, because
`updatedAt` moves when a volume joins a series that has existed for years — which is exactly the event
a reader following that series wants to see, and which `new` will never show them.

### A `sort` parameter is rejected, not ignored

`400 invalid_query`. Honouring an override would put the client-to-client disagreement straight back;
**ignoring it silently would be worse**, because the caller would believe they had changed something
and would report the feed as broken.

A caller who wants a different order wants the general listing, which still accepts everything.

### Feeds live under `/feeds/`, not at the collection root

`/series/new` would be indistinguishable from `/series/{seriesId}` for a series whose identifier — or,
with a friendlier scheme later, whose slug — is `new`. Ktor happens to prefer a literal segment over a
parameter, but relying on that makes the routing depend on a framework detail rather than on what the
file says, and it would make such a series **unreachable** rather than merely awkward.

### `on-deck` and `keep-reading` are media items only

Both describe what *this reader* has started, which is a property of an item, not of a series. Mounting
them under `/series` would answer a question nobody asked, with a filter that means nothing there. A
test asserts the absence, so the asymmetry is deliberate rather than an oversight someone later
"fixes".

The other three are defined purely by ordering, which is why they apply to both collections.

### Every feed is newest-first

Asserted as a property across the whole enum rather than per feed. A discovery shelf that put the oldest
item first would be a list, not a shelf, and a new feed added later should have to justify departing
from that rather than inherit it by accident.

## Consequences

- Five feeds on media items, three on series, all paged like every other listing.
- The definitions are pinned by a unit test on the enum as well as by HTTP tests, so changing what
  `new` means requires editing an assertion that says what it meant.
- Verified by mutation: honouring a `sort` override fails `refuses to reorder a named feed`, and making
  the `on-deck` feed forget its filter fails both its HTTP test and the enum test.
- The OpenAPI drift test caught all eight routes before review did, and each is documented with its
  meaning rather than only its shape — a feed whose ordering is its contract is not described by
  listing its parameters.
