# ADR 0048: Indexed and structured catalog search

## Status

Accepted.

## Context

The compatibility query parameter initially used escaped `%LIKE%` predicates
against a few title columns. That becomes a table scan on large libraries and
does not cover metadata fields or Komga's recursive search-condition wire
format.

## Decision

- Use SQLite FTS5 with the Unicode tokenizer and diacritic folding.
- Index separate Book and Series documents containing canonical and alternate
  titles, summaries, contributors, roles, tags, genres, publisher, language,
  ISBN, and links.
- Build initial documents in the schema migration and refresh them in the same
  transaction as metadata updates. Book contributor changes also refresh the
  parent Series document; Series metadata changes refresh child Book documents.
- Convert user input to quoted prefix tokens joined with `AND`. Raw FTS syntax
  is never accepted, and punctuation-only input deterministically matches
  nothing.
- Represent structured search as a framework-independent recursive condition
  tree in `core:application`.
- Parse the Komga `allOf`/`anyOf` and typed operator JSON with depth and node
  limits, target-specific field validation, date/duration validation, and
  object-value validation.
- Compile conditions to bound SQL predicates for membership, metadata,
  read-state, media-state, artwork, date, numeric, string, nullable relation,
  and aggregate Series fields. Authorization and content restrictions remain
  mandatory outer predicates.
- Bundle the checksum-pinned Komga 1.25.0 OpenAPI contract and serve it without
  authentication at `/v3/api-docs`.

## Consequences

Search cost scales with the FTS index instead of catalog size for free text.
Metadata and index changes commit atomically, and migration upgrades existing
catalogs without a separate reindex job. Structured conditions cannot inject
SQL or FTS syntax because field/operator mappings are closed and all values are
bound. The bundled API document stays byte-identical to the audited upstream
baseline.
