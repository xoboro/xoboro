# ADR 0077: Certify One-Shot and Sibling Navigation Parity

- Status: accepted
- Date: 2026-07-28

## Context

The live catalog suite covered detail resources and missing sibling
boundaries, but its single-book fixture could not prove successful previous
and next traversal. It also did not exercise Komga's one-shot convention,
where a file below the configured one-shot directory becomes both a Book and
a synthetic one-Book Series.

Komga promotes selected Book metadata into that synthetic Series. Merely
detecting the directory therefore leaves observable Series title, summary,
status, and total count incompatible.

## Decision

- Build two regular synthetic CBZ books and one synthetic standalone CBZ in
  the live differential workflow.
- Resolve generated regular and one-shot IDs from their observable metadata
  instead of relying on database insertion order.
- Compare successful previous and next traversal, both outer boundaries,
  one-shot Book detail, one-shot Series detail, and the one-shot filter.
- Add a final Series metadata provider that promotes the first one-shot
  Book's title and summary, marks the Series ended, and fixes its total count
  to one, matching Komga's provider precedence.
- Treat default unsorted REST result arrays and equal-timestamp OPDS latest
  publication arrays as sets in the harness. Their complete elements remain
  compared; database-dependent tie order is not a contract.

## Consequences

The authenticated catalog suite now contains 29 live cases. It certifies
successful and missing sibling navigation and exact one-shot Book/Series
projection against the digest-pinned Komga 1.25.0 container.

One-shot metadata promotion is isolated from ComicInfo and EPUB parsing, so
future source adapters can reuse the same domain rule after producing Book
metadata.
