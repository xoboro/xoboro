# ADR 0040: Interoperability progress, history, and sync state

- Status: accepted
- Date: 2026-07-27

## Context

Komga exposes Mihon-specific sequential progress, ComicRack CBL matching,
administrator history, and per-user sync-point cleanup. These surfaces must not
leak compatibility DTOs into the portable media domain, and catalog-scale
matching and history reads must avoid per-item database queries.

## Decision

- Model sequential progress as portable indexed and numbered application
  results. The Komga adapter maps them to the legacy Tachiyomi route names.
- Parse CBL with external entities, schemas, and DTDs disabled. Enforce a
  five-megabyte request bound before parsing.
- Match CBL series aliases and issue numbers with chunked SQLite `VALUES`
  joins. Match titles case-insensitively and issue numbers without leading-zero
  sensitivity while excluding logically deleted catalog rows.
- Persist historical events and their string properties transactionally.
  Retrieve properties in bounded batches after stable database paging.
- Persist sync points by user and optional API-key identity. Support atomic
  user-wide or selected-key deletion and rely on foreign-key cascades for
  credential removal.
- Keep ComicRack, Mihon, and Komga wire details in the compatibility module;
  the application and domain modules expose source- and client-neutral
  contracts.

## Consequences

Large reading lists do not trigger one query per requested book, history pages
do not trigger one query per event, and malformed XML cannot resolve local or
remote entities. The same progress and sync foundations can later serve OPDS,
Kobo, KOReader, and native Xoboro clients without introducing Comic-specific
roots into `Library -> MediaItem`.
