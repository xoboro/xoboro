# ADR 0023: Extensible library media-item hierarchy

- Status: accepted
- Date: 2026-07-27

## Context

Xoboro starts with Komga-compatible comics and ebooks, but it must later support
Jellyfin-class video and audio libraries without rebuilding identity, storage,
progress, search, or authorization around another root entity.

Komga's `Book` is both an API term and a storage record. It cannot remain the
root domain abstraction because comic, novel, video, and audio behavior differs
substantially.

## Decision

Use this portable domain hierarchy:

```text
Library
└── MediaItem
    ├── Comic
    ├── Novel
    ├── Book
    ├── Video
    └── Audio
```

- `MediaItem` owns common identity, library ownership, source location,
  filesystem state, deletion state, and lifecycle timestamps.
- `Comic`, `Novel`, and `Book` can participate in Komga-compatible series.
  `Video` and `Audio` do not require a series.
- Behavior is expressed through capabilities:
  page sequence, reflowable text, timeline, image, video, and audio content.
  Consumers query capabilities instead of switching on file extensions.
- Semantic type is independent from physical format. A PDF can be a comic or a
  book; metadata and future user overrides may change the type without moving
  the source.
- Existing Komga rows receive a durable semantic discriminator. The initial
  migration maps comic archives to `Comic`, EPUB to `Novel`, and PDF to `Book`.
- `MediaItemRepository` is the canonical library-facing read boundary.
  `BookRepository` remains an internal Komga catalog compatibility boundary
  while the remaining write lifecycle is migrated.
- Existing media-item IDs stay unchanged. `BookId` is a source-compatible alias
  of the canonical `MediaItemId`.

## Progress model boundary

- Page-based items store a page or locator.
- Timeline items store a time position and duration.
- Progress synchronization will share media-item identity and conflict metadata
  but will not force page and timeline positions into one nullable record.

## Consequences

Video and audio support can reuse libraries, users, restrictions, sources,
tasks, search, and downloads. Format-specific analyzers and progress types
remain separate, avoiding a wide base entity filled with unrelated nullable
fields.
