# ADR 0073: Complete OPDS v2 Feed Matrix

- Status: accepted
- Date: 2026-07-28

## Context

The root, browse, and search feeds matched Komga, but the remaining OPDS v2
navigation matrix still used generic feed builders. Collection and read-list
feeds omitted library tabs and groups, detail feeds omitted paging and stable
owner timestamps, and series feeds did not expose descriptions or tag facets.

## Decision

- Apply library-aware modification timestamps to keep-reading, on-deck,
  latest-book, and latest-series feeds.
- Page and sort collection/read-list listings by name, retain library tabs,
  and emit the same named groups as Komga.
- Filter organization detail routes through the authenticated catalog before
  exposing their names or members.
- Preserve manual organization order; otherwise apply Komga's title or release
  date order before paging.
- Add series summary fallback, book-tag filtering, and OPDS tag facets backed
  by the SQL metadata facet repository.
- Seed the live differential fixture with synthetic ComicInfo metadata,
  collection membership, and read-list membership.
- Certify all remaining feed categories, details, compact publications, and
  tag facets against the digest-pinned Komga 1.25.0 image.

## Consequences

The authenticated catalog differential suite covers 16 cases and every OPDS
v2 feed category. Generated origins, identifiers inside links, and timestamps
remain the only ignored JSON fields.

Komga's organization search uses an asynchronous Lucene index, so the live
title-search fixture deliberately uses a term that does not overlap newly
created organization names. Organization search behavior remains covered by
its dedicated REST and synthetic tests without making differential CI
timing-dependent.
