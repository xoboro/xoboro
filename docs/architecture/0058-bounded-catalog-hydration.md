# ADR 0058: Bounded catalog hydration and exact series aggregation

- Status: accepted
- Date: 2026-07-27

## Context

Catalog pages first selected matching identifiers in SQLite, then hydrated every
Book or Series through several single-entity repository calls. The number of
queries therefore grew with the page size. Series hydration also diverged from
Komga by selecting blank summaries and the latest release date.

The public repository ports still need useful single-entity defaults for
in-memory and future non-SQL adapters, while the SQLite adapter must keep query
counts bounded.

## Decision

- Add ordered bulk-read operations to the Book, Series, metadata, media, and
  read-progress repository ports.
- Let portable ports retain conservative single-entity defaults; override every
  bulk operation in the jOOQ adapter.
- Select and authorize catalog identifiers in SQL, then hydrate each relation in
  chunks of at most 500 identifiers.
- Reassemble results in the identifier-page order so batching cannot change API
  ordering.
- Aggregate Series book metadata in set-based SQL using Komga's rules:
  - use the first non-blank summary in metadata number order;
  - use the earliest release date;
  - preserve first-seen author order while deduplicating by role and name;
  - expose the union of book tags;
  - use the earliest creation and latest update timestamps.
- Keep all values bound and cap `IN` lists so large unpaged requests do not
  exceed SQLite parameter limits.

## Consequences

Catalog query counts now grow with bounded chunks rather than with every result.
Book media subtrees, parent metadata, and per-user progress are fetched in bulk
without mixing owner rows. Pages that cross the 500-identifier boundary retain
stable ordering and complete nested state. Other persistence adapters may use
the portable defaults until they provide their own batch implementation.
