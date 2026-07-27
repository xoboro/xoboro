# ADR 0072: OPDS v2 Feed Parity

- Status: accepted
- Date: 2026-07-28

## Context

Xoboro implemented every Komga OPDS v2 route, but its recommended, browse,
and search feeds still differed in client-visible structure. Paging metadata,
subsection navigation, group links, title-only search, publisher facets, and
compact catalog publications were not exact. Acquisition links also pointed
at the REST download route instead of Komga's OPDS alias.

## Decision

- Generate Komga-compatible start, search, self, previous, next, and
  subsection links.
- Include exact one-based paging metadata in feeds and recommended groups.
- Expose publisher navigation from the access-filtered metadata facet
  repository.
- Search titles as an all-terms condition with Komga's fixed 20-item limit,
  including separate Series, Books, Collections, and Read Lists groups.
- Keep catalog publications compact instead of embedding full manifests.
  Preserve authentication properties, series navigation, and the OPDS
  acquisition URL.
- Serve `/opds/v2/books/{bookId}/file` through the same authorized,
  range-capable streaming boundary as the REST download.
- Compare catalog, browse, and search against the digest-pinned Komga 1.25.0
  image in CI, ignoring only generated timestamps, origins, and identifiers
  embedded in links.
- Verify that both implementations emit shallow ETags for all three feeds.
  Their generated `modified` timestamp deliberately makes the representation
  dynamic, so a later conditional request is not guaranteed to return 304.

## Consequences

OPDS v2 clients see the same navigation and compact-publication model when
switching from Komga to Xoboro. Feed generation continues to reuse the
database-paged catalog and authorization boundaries, while publisher facets
remain SQL-backed rather than being materialized in memory.

The authenticated live differential suite now checks seven catalog cases.
Advanced multi-item relevance ordering and failure fixtures remain separate
parity work.
