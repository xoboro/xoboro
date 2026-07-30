# 0101 — Local-library acceptance through the native API only

## Status

Accepted. Two named remainders; see Consequences.

## Context

`Local-library end-to-end acceptance` is a release gate, and nothing covered it. What existed covered
adjacent things:

- `XoboroNativeCatalogApplicationTest` seeds the database directly, so it proves the read surface serves
  persisted rows — never that a **scan** produces them.
- The performance harness scans a generated tree, but measures instead of asserting, and never restarts.
- Neither walks navigation, progress, or delivery against a catalog a scan built.

So the one thing a release gate is about — a client doing what a reader does, end to end — was untested.

## Decision

### Every step goes through HTTP, including the parts that would be easier not to

Library creation and the scan trigger go through `POST /libraries` and `POST /libraries/{id}/scan`
rather than by calling the scanner on `runtime`. Calling the scanner directly would test the scanner
while skipping the contract a client actually uses, and the contract is what the gate is about.

Waiting for the scan is done by **polling the public listing**. A client has no other way to know a
scan finished, so if it cannot be observed there it cannot be observed by a real reader either. Polling
rather than sleeping: a sleep long enough to be reliable on a slow machine wastes that time every run,
and one short enough to be quick is a flake waiting to happen.

### Real CBZ files with decodable images

The archives contain real JPEGs, not arbitrary bytes. The analyzer reads dimensions, so a page has to be
an image or the item never reaches `READY` — and the delivery assertion would then be testing an error
path while looking like it tested delivery.

### Three items, asserted from the middle

Navigation is walked from the middle item outwards. An item with both neighbours is the only one that
can show the walk is *ordered* rather than merely non-empty. The two ends assert `404`, because a reader
at the last item must not silently loop back to the first.

### Two properties across a restart, not one

The second runtime asserts the same **identifiers** exist and the same **reading order** holds. These
are different claims, and conflating them is what made the first draft of this test fail:

- Identifier stability matters because a restart that re-derived ids breaks every client bookmark.
- Reading-order stability is separate, and comparing raw listing order would assert something nobody
  promised — the listing's default sort is **not** the in-series reading order.

That distinction cost two debugging rounds and is recorded because the mistake is easy to repeat:
`previous` returned `200` while `next` returned `404` for what the listing called the middle item, and
the server was right both times.

### Deletion safety is asserted as recoverability, not as absence

A vanished file leaves the live listing and appears under `trashed=true`. Asserting only that it left
the listing would pass just as well if reconciliation had destroyed it, which is the failure this
property exists to prevent: a scan against a temporarily unavailable mount must not delete reading
history.

The unchanged-rescan assertion states the same safety positively — nothing is removed or re-created
merely because a scan ran again.

## Consequences

- The gate is covered for a small tree. Both tests were mutation-checked: forcing the trashed filter to
  `false` fails the recoverability test, and answering a stale progress write with `200` instead of
  `409` fails the navigation test.
- **Two remainders, and the coverage row names them.** The same walk at a size where it takes minutes
  rather than seconds, and against a disposable real-world tree rather than a generated one. Neither is
  something a synthetic test on this machine can stand in for, and calling the row READY on the strength
  of a three-file library would be the overclaim the row exists to prevent.
- `Page and Readium locator progress` moves to READY: the mutation/conflict contract is now exercised
  end to end rather than only at the unit level.
- `Mark read/unread, keep reading, and previous/next navigation` keeps only the administrator UI as
  remaining work.
