# ADR 0028: Source-aware metadata foundation

## Context

Catalog identity and media analysis are insufficient for Komga-compatible
series and book responses. Metadata arrives from ordered sources, includes
one-to-many relations, must survive restarts, and must not overwrite fields
locked by an administrator. Future Novel, Video, and Audio implementations also
need the same source boundary without inheriting comic container assumptions.

## Decision

- Keep portable `BookMetadata` and `SeriesMetadata` aggregates in the domain
  module. Persist authors, links, tags, genres, alternate titles, sharing
  labels, field locks, and import timestamps as normalized relations.
- Initialize metadata for every catalog row through database triggers and
  backfill existing rows during migration.
- Model importers as ordered application ports. Each provider returns a patch,
  and the application lifecycle merges patches while preserving locked fields.
- Model sidecar and archive access as source-specific ports. Local access
  canonicalizes roots, prevents path and symlink escapes, and enforces byte
  limits before parsing.
- Parse root `ComicInfo.xml` entries with DTD and external entities disabled.
  Import Mylar `series.json` separately at the series directory boundary.
- Run library-wide metadata refresh as durable, deduplicated book and series
  tasks. Book work completes before a series task aggregates its sources.
- Keep local artwork, EPUB metadata, manual patches, and remote-source readers
  separate. Their absence must remain visible in the parity ledger.

## Consequences

Metadata reads and future catalog APIs no longer need to reopen media files.
Refresh can be retried safely and respects user locks across restarts. New
media types can add providers without changing `MediaItem` identity, while
comic-only parsing remains isolated in the metadata adapter.
