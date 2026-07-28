# ADR 0080: Page Organization Members in the Catalog Query

- Status: accepted
- Date: 2026-07-28

## Context

Komga applies authorization, content restrictions, legacy metadata filters,
membership ordering, counting, and pagination when listing the Series in a
Collection or the Books in a Read List.

Xoboro previously hydrated every member individually, filtered only by
Library, sorted in application memory, and then sliced the result. This
omitted the endpoint-specific filters and made response cost proportional to
the complete organization size.

## Decision

- Represent Collection and Read List membership with the existing structured
  catalog predicates.
- Parse only the legacy filters declared by the corresponding Komga endpoint.
- Execute membership, authorization, restrictions, filters, count, ordering,
  and pagination in the shared SQL catalog query.
- Preserve explicit member positions for ordered organizations and Komga's
  metadata fallback order for unordered organizations.
- Determine non-administrator organization visibility with a one-row
  access-filtered membership query instead of hydrating every member.

## Consequences

Member list responses have database-accurate totals and page envelopes.
Large Collections and Read Lists no longer require full member hydration, and
the same authorization and content-restriction boundary used by the main
catalog applies before paging.

The top-level organization lists still hydrate their visible membership to
produce Komga's filtered DTOs. A dedicated organization projection can replace
that path independently if organization counts become large.
