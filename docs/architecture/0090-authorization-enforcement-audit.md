# 0090 — Authorization enforcement audit

## Status

Accepted.

## Context

`docs/feature-coverage.md` carried "Audit enforcement across every native and protocol interface" as
the remaining work for roles, library grants, age ratings and sharing-label restrictions. Unlike the
other open rows, a gap here is a vulnerability rather than a missing feature, so the audit was run
before further hardening work.

Xoboro has three authorization dimensions:

- **Roles** (`UserRole`) — coarse capabilities: `ADMIN`, `FILE_DOWNLOAD`, `PAGE_STREAMING`,
  `KOBO_SYNC`, `KOREADER_SYNC`.
- **Library grants** — `User.canAccessLibrary`, derived from `sharesAllLibraries` and
  `sharedLibraryIds`.
- **Content restrictions** — `ContentRestrictions`: an age restriction in `ALLOW_ONLY` or `EXCLUDE`
  mode, plus allow/exclude sets of sharing labels.

The audit enumerated every route group in `server/api` (native) and `compatibility/komga-api`
(Komga REST, OPDS v1/v2, Kobo, KOReader, Tachiyomi, ComicRack) and asked, for each, which of the
three dimensions it enforces and by what mechanism.

## Decision

### Roles are enforced at the route, restrictions in SQL

Roles are checked inline at the route that needs them, which is appropriate: a role gates an
operation, and the operation is what the route names.

Library grants and content restrictions are not checked at the route. They are carried in
`CatalogAccess` and pushed into the query as SQL predicates by
`JooqCatalogReadRepository.addLibraryFilter` and `addContentRestriction`. This is the correct place
for them: a filter that runs in the database cannot be forgotten by a caller that pages, sorts,
counts or searches, and the predicate applies identically to `findBooks`, `findBookByIdOrNull`,
`findPreviousBookOrNull`, `findNextBookOrNull`, `findSeries`, `findSeriesByIdOrNull` and
`countSeriesByFirstCharacter` because all seven route through the same `bookFilter`/`seriesFilter`.

`CatalogReadRepository` takes `access: CatalogAccess` as a **required** parameter on every method,
including the by-id lookups. That is what makes the design hold: there is no overload that reads the
catalog without an authorization envelope, so a new call site cannot omit one by accident. The SQL
predicates were checked against `User.isContentAllowed` and agree on all four cases (`ALLOW_ONLY`
OR-combines the age and label tests; `EXCLUDE` denies independently of them).

The audit confirmed that every content-serving interface — native delivery, artwork, progress,
metadata, collections, OPDS, Kobo, WebPub, archive download, Tachiyomi — resolves entities through
`CatalogReadRepository` with the caller's access. Collection and read-list contents are filtered to
visible members and report a `filtered` flag rather than leaking membership counts.

### Finding 1: the access projection was duplicated five times (fixed)

The `User` → `CatalogAccess` projection existed as five byte-identical copies under five names:
`catalogAccess`, `nativeCatalogAccess`, `mediaAccess`, `progressAccess`, `tachiyomiAccess` — one per
route group. Copies are the wrong shape for an authorization rule. Adding a dimension to
`ContentRestrictions` would have required five edits, and forgetting one would have left that
interface silently under-enforcing while every test covering the other four still passed. The
duplication was not itself an exploit, but it is the mechanism by which the next one would arrive.

It now exists once, as `User.catalogAccess()` in `core/application`, beside the `CatalogAccess` it
produces.

### Finding 2: KOReader fingerprint lookups ignored content restrictions (fixed)

`KoreaderSyncLifecycle` was the only route group in either module that took a raw `BookRepository`
and resolved entities outside the access-checked catalog. It matched a client-supplied KOReader
partial-MD5 fingerprint against the fingerprint index and filtered the results on
`deletedAtMillis == null && user.canAccessLibrary(it.libraryId)` — library grants only.

Content restrictions were therefore unenforced on that path. A caller with an age restriction or an
excluded sharing label, holding a legitimate grant on the library, could read **and overwrite**
reading progress for an item the catalog hides from them, and could confirm the item's existence by
the status code. Both the `GET /koreader/syncs/progress/{bookHash}` and
`PUT /koreader/syncs/progress` paths were affected because both resolve through the same helper.

A fingerprint is supplied by the client and is not a capability: matching one must not reveal an item
the caller cannot otherwise see. Resolution now goes through
`CatalogReadRepository.findBookByIdOrNull` with the caller's `catalogAccess()`.

An item the caller cannot see is treated as **absent, not as a conflict**. When two items share a
fingerprint and only one is visible, the visible one resolves rather than returning `409`. Restricted
content must not change the outcome for a caller who is not permitted to know it exists — a conflict
response would itself disclose that a second, hidden match exists.

## Consequences

- One definition governs what any interface may see. A new restriction dimension is added to
  `ContentRestrictions`, projected once, and enforced everywhere the projection is used.
- KOReader sync now denies restricted content on both read and write.
- `KoreaderSyncLifecycle` depends on `CatalogReadRepository` instead of `BookRepository`.
- Two tests cover the fix, and both were verified to fail against the previous implementation:
  `hides age-restricted media items from fingerprint lookups` (which failed before the fix) and
  `hides media items in unshared libraries from fingerprint lookups` (which passed before, and now
  locks in the dimension that was already enforced). The age-restriction test asserts the
  administrator can resolve the same fingerprint first, so a denial cannot be produced by a fixture
  that has no matching item.
- Roles remain checked at the route. This audit did not consolidate them, because a role gates an
  operation rather than a set of rows and inlining the check next to the operation keeps it readable.
