# ADR 0082: Feature coverage over wire compatibility

- Status: Accepted
- Date: 2026-07-28
- Supersedes: ADR 0002

## Context

Xoboro uses Komga 1.25.0 as a mature reference for comic and ebook server
capabilities. Exact emulation of Komga's REST paths, response envelopes,
database schema, authentication internals, and accidental behavior does not
improve the Xoboro product. It can instead preserve security weaknesses,
performance bottlenecks, and framework-specific constraints.

Existing work already separates portable domain and application services from
Komga-shaped HTTP DTOs. Standards-based clients and historical data still need
explicit interoperability boundaries.

## Decision

- Measure the replacement goal by user-visible feature coverage, not endpoint
  or source-line parity.
- Design a versioned Xoboro-native API around Xoboro domain concepts and use
  cases.
- Keep standards-based protocols such as OPDS, Kobo Sync, and KOReader Sync
  compatible with their clients.
- Keep the Komga database importer as a one-way migration boundary.
- Retain existing Komga-shaped routes only as transitional adapters while the
  native API and UI are built. They are not a release gate and may be removed.
- Reimplement unsafe, racy, inefficient, or tightly coupled behavior with
  secure and measurable Xoboro internals.
- Record deliberate behavioral differences and migration consequences instead
  of hiding them as compatibility failures.

## Consequences

The existing compatibility code remains useful test evidence and temporary
access to implemented use cases, but new functionality does not need Komga wire
DTOs. Differential tests may continue as regression tools for protocol or
migration behavior; exact REST response matching is no longer required.

The release gate is `docs/feature-coverage.md`.
