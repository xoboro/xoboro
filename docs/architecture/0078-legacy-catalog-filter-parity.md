# ADR 0078: Route Legacy Catalog Filters Through Structured Search

- Status: accepted
- Date: 2026-07-28

## Context

Komga retains deprecated `GET /api/v1/books` and `GET /api/v1/series`
endpoints for older clients. Xoboro accepted their query parameters but
silently ignored Book media, read-state, date, and tag filters and several
Series organization, metadata, completion, contributor, and read-state
filters.

The Series tag contract also includes tags aggregated from child Books.
Filtering only the Series metadata relation therefore returns a different
catalog from Komga.

## Decision

- Translate deprecated query parameters into the same bounded
  `CatalogSearchCondition` tree used by current POST search endpoints.
- Preserve repeated query values as an `anyOf` group and combine distinct
  fields with `allOf`.
- Map Komga's release-year interval, nullable age rating, contributor pair,
  completion, and boolean semantics explicitly.
- Route publisher, language, genre, and tag through structured predicates
  instead of maintaining a second SQL implementation.
- Keep Series tag matching on the persisted Series/Book metadata aggregation.
- Certify both deprecated and structured compound filters against a
  digest-pinned Komga 1.25.0 container using metadata-rich synthetic CBZ
  fixtures.

## Consequences

Legacy and current catalog clients now share one authorization-aware SQL
compiler and one set of field semantics. Book filters cover media status,
read status, release date, and tags. Series filters cover collections,
status, read status, publisher, language, genre, tags, age rating, release
year, sharing labels, completion, and contributors.

The authenticated live catalog suite contains 33 cases and detects regressions
in compound metadata filtering without embedding private catalog data.
