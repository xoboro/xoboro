# ADR 0086: Native media catalog

- Status: accepted
- Date: 2026-07-28

## Context

The first-party clients need library, series, and item discovery before the
reader UI can move away from the transitional Komga routes. Naming the native
resource `book` would also preserve a comics-only constraint while Xoboro's
domain already models comic, novel, book, video, and audio media items.

Catalog access must enforce library grants, age and sharing-label restrictions,
and per-user progress in the database query rather than filtering a complete
result in the HTTP process. Filesystem and future remote-source identifiers
must not be exposed to reader accounts.

## Decision

- Expose read-only `/libraries`, `/series`, and `/media-items` resources below
  `/api/xoboro/v1`.
- Map the current persisted book read model into the common media hierarchy:
  comic archives are `COMIC`, EPUB is `NOVEL`, and PDF is `BOOK`.
- Keep page-based storage queries for now, with an explicit native envelope,
  a public maximum size of 200, validated sort names, and stable tie-breakers
  supplied by the repository.
- Translate public sort and filter names at the HTTP boundary. Do not expose
  SQL or compatibility-specific property names.
- Pass the authenticated user's library grants and content restrictions to
  `CatalogReadRepository` for every query and lookup.
- Return `404` for both missing and unauthorized identifiers. Include library
  source provider and location only for administrators.

## Consequences

Web and mobile clients can browse a persistent Xoboro catalog without Komga
DTOs. The same response model can grow to video and audio, and authorization
remains push-down rather than an in-memory post-filter.

Native artwork, content delivery, metadata mutation, read-progress commands,
library administration, cursor pagination, and OpenAPI remain follow-up
contracts.
