# ADR 0027: Durable library analysis and trash maintenance

- Status: accepted
- Date: 2026-07-27

## Context

Komga accepts library-wide analysis and empty-trash requests asynchronously.
Large libraries can contain many books, so neither media analysis nor cascading
catalog deletion belongs in an HTTP call. Cleanup also needs to run
automatically after successful scans when the library setting requests it.

## Decision

- Expose library maintenance to the compatibility layer through a portable
  requester rather than task-module types.
- Expand an analysis request into one high-priority durable task per active
  book. Reuse stable task IDs and series group IDs for deduplication and
  per-series exclusion.
- Represent trash cleanup as one deduplicated, high-priority durable task per
  library.
- Delete logically removed books and their dependent media first, then delete
  logically removed series only when they have no remaining books. Perform both
  statements in one database transaction.
- Invoke post-scan hooks only after catalog reconciliation succeeds. When
  `emptyTrashAfterScan` is enabled, emit the same durable cleanup task used by
  the administrator endpoint.
- Preserve Komga's observable missing-library behavior: analysis of an unknown
  ID is accepted as an empty selection, while empty-trash returns not found.

## Consequences

Requests remain fast and restart-safe regardless of catalog size. Existing
worker leasing, retry, deduplication, and dead-letter behavior applies to both
maintenance paths. Metadata refresh remains a distinct job family because it
requires metadata import and artwork services rather than media analysis.
