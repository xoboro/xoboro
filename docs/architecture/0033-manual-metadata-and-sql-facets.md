# ADR 0033: Presence-sensitive metadata edits and SQL facets

## Context

Komga metadata updates distinguish an omitted field from an explicit JSON
`null`. Catalog facet endpoints also combine library authorization, age and
sharing-label restrictions, organization membership, and pagination. Loading
the catalog into application memory would make these endpoints increasingly
expensive as libraries grow.

The Xoboro domain is rooted at `Library` and `MediaItem`, with Comic, Novel,
Book, Video, and Audio variants. Komga Book and Series remain compatibility
adapters for the Comic-oriented API rather than becoming the shared root.

## Decision

- Represent manually editable nullable values with a presence-sensitive patch
  field so omission preserves a value and explicit `null` clears it.
- Keep metadata refresh provider patches separate from administrator editing
  patches. Provider refresh continues to respect field locks; an administrator
  can change both values and locks.
- Normalize metadata through the domain repositories and keep bulk book edits
  tolerant of missing targets, matching Komga's observable loop behavior.
- Calculate author and metadata facets with `DISTINCT` SQL. Apply authorized
  library intersections, content restrictions, series/collection/read-list
  membership, search, and role predicates before distinct projection,
  counting, and paging.
- Expose the behavior through the Komga compatibility adapter. Future native
  APIs can project common `MediaItem` metadata without inheriting Comic route
  names.

## Consequences

Large libraries do not need an application-memory catalog scan for each filter
menu. Explicit nullable edits survive SQLite round trips. The implementation
still needs differential fixtures for Komga validation envelopes, ordering,
and event side effects before these operations can be certified compatible.
