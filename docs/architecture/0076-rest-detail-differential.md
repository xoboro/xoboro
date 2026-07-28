# ADR 0076: Certify REST Detail and Relation Contracts

- Status: accepted
- Date: 2026-07-28

## Context

The synthetic live differential suite certified paged Book and Series lists,
but not the corresponding detail resources. A list response could therefore
match Komga while detail lookup, Series-to-Book projection, sibling
boundaries, or reverse Collection/ReadList relations silently diverged.

These routes are central to navigation in web, mobile, Mihon, and automation
clients. Their identifiers and filesystem timestamps differ between isolated
reference and candidate databases, so those fields require narrow
normalization rather than whole-body exclusion.

## Decision

- Compare Book and Series detail documents against the digest-pinned Komga
  1.25.0 image.
- Compare the deprecated Series Books page because existing clients still use
  it and Komga promises its default natural number ordering.
- Compare both previous and next boundary failures, including the timestamped
  Spring error envelope.
- Compare Series Collections and Book ReadLists reverse relations after the
  workflow creates matching ordered synthetic organizations.
- Ignore only generated IDs, source timestamps, and relationship ID arrays.
  Continue comparing all metadata, media, counts, flags, ordering, names, and
  summaries.

## Consequences

The authenticated catalog suite now contains 24 live cases. Book detail,
Series detail, Series Books, both sibling boundaries, and both reverse
organization relations are structurally certified against Komga.

Successful sibling traversal across multiple books and one-shot detail
behavior remain separate fixture expansions; boundary semantics no longer
depend solely on unit tests.
