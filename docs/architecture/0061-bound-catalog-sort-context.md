# ADR 0061: Bind catalog sort context

- Status: accepted
- Date: 2026-07-27

## Context

Komga accepts named Book and Series sort properties rather than arbitrary SQL.
Several properties depend on the authenticated user or on the read list or
collection currently being browsed. A global minimum membership position is
incorrect when an item belongs to more than one ordered organization.

Dynamic sort properties also need the same injection resistance and stable
pagination guarantees as structured catalog filters.

## Decision

- Maintain explicit allowlists for every Komga Book and Series sort alias.
- Join Book and Series progress with the authenticated user ID as a bound
  parameter; anonymous queries use an always-empty join.
- Resolve `readList.number` and `collection.number` against the single matching
  equality predicate in the structured query and bind that organization ID.
- Fall back to the minimum membership position only when no unambiguous
  organization context exists.
- Append the entity ID as a deterministic tie breaker to every requested sort.
- Keep random Series ordering as the sole intentionally unstable sort.
- Reject unknown sort properties before executing SQL.

## Consequences

Sorting now matches the selected user's progress and the selected
organization's manual order even when memberships overlap. All request-derived
values remain bound, aliases cannot inject SQL, and ordinary paged sorts remain
stable across requests.
