# ADR 0081: Batch Organization Visibility Hydration

- Status: accepted
- Date: 2026-07-28

## Context

Komga's Collection and Read List DTOs expose both ordered member identifiers
and a `filtered` flag. Xoboro must therefore apply the requesting user's
library grants, sharing-label restrictions, and age restrictions before
returning top-level lists, details, reverse relations, or Read List siblings.

The original adapter looked up every referenced Series or Book separately.
Listing many organizations consequently produced a query count proportional
to the total number of memberships.

## Decision

- Build an `anyOf` structured membership condition for each bounded batch of
  at most 500 Collection or Read List identifiers.
- Resolve visible members through the shared catalog read repository so its
  authorization and content-restriction predicates remain the single source
  of truth.
- Index each batch result by member identifier, then restore every
  organization's persisted member order while constructing its DTO.
- Use the same projection for top-level lists, details, reverse relations,
  and Read List sibling navigation.
- Retain the existing administrator-only visibility of empty organizations
  when no Library filter is requested.

## Consequences

Visible-member query count is bounded by the number of 500-organization
batches instead of total membership count. DTO ordering, filtered indicators,
and access semantics remain unchanged.

Organization rows are still loaded and paged in application memory. Moving
their name search and page envelope into a dedicated SQL projection remains an
independent optimization because filtered DTOs still require visible member
identifiers.
