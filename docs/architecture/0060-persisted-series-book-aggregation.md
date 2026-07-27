# ADR 0060: Persisted Series book-metadata aggregation

- Status: accepted
- Date: 2026-07-28

## Context

Series DTOs expose an aggregation of their visible Books: the first non-blank
summary, earliest release date, distinct ordered authors, tag union, and
metadata timestamps. Bounded batch hydration removed the per-Series N+1 query,
but every catalog request still rebuilt those values from all child Books.
Komga stores the same projection and refreshes it when Book metadata changes.

Repeated joins are particularly expensive for large libraries and for filters
that depend on aggregate release dates, authors, or tags.

## Decision

- Persist the scalar aggregation, ordered authors, and tags in dedicated
  Series-owned tables.
- Record affected Series IDs in a unique dirty set through SQLite triggers for:
  Book insertion, deletion, move, path/lifecycle change, Book metadata change,
  and author/tag relation change.
- Rebuild dirty Series in transactions and batches of at most 500 IDs.
- Rebuild both the old and new parent after a Book move.
- Keep dirty marking in the same transaction as its source mutation.
- Remove a dirty marker only after the complete scalar/author/tag projection is
  committed.
- Refresh the dirty set before Series filtering, sorting, grouping, or
  hydration, then read only the persisted projection.
- Use the persisted earliest release date and aggregate relations for structured
  Series search, matching Komga's aggregation semantics.

## Consequences

Unchanged Series pages no longer scan or join child Book metadata. Mutation
cost is proportional to affected Series, restart-safe, and idempotent. A process
restart or database import retains dirty work. The first Series read after a
large migration may rebuild multiple 500-ID batches; subsequent reads use the
indexed projection only.
