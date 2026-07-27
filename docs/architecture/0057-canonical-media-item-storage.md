# ADR 0057: Canonical Library-owned MediaItem storage

- Status: accepted
- Date: 2026-07-28

## Context

The portable domain already models:

```text
Library
└── MediaItem
    ├── Comic
    ├── Novel
    ├── Book
    ├── Video
    └── Audio
```

The initial persistence adapter still stored every semantic type in Komga's
`book` table. That requires a Series even for Video and Audio, contradicts the
domain boundary, and would force future Jellyfin-class media support through
comic-specific columns.

## Decision

- Persist common identity, Library ownership, source identity/location, file
  facts, lifecycle timestamps, semantic type, and optional timeline duration in
  a canonical `media_item` table.
- Keep `book` as the Komga document adapter for Comic, Novel, and Book. Its
  primary key is also the canonical MediaItem ID.
- Backfill every existing Book without changing IDs.
- Synchronize Book inserts, updates, soft deletion, moves, and hard deletion
  into the canonical row inside the same SQLite transaction.
- Persist Video and Audio directly as timeline MediaItems without a synthetic
  Series or Book.
- Expose document and timeline items through one `MediaItemRepository`, with a
  separate timeline write port so document writes cannot bypass Komga
  invariants.
- Keep semantic type independent of file format. The Book adapter may continue
  to represent an explicitly classified document while the canonical row owns
  the type.

## Consequences

Existing Komga IDs, foreign keys, APIs, imports, and scanner writes remain
stable. Native Video and Audio scanners can be added without schema surgery or
fake comic hierarchy. During the compatibility phase, document-specific
metadata, progress, and search tables still reference `book`; they will move to
MediaItem-owned projections incrementally while Komga routes keep their
adapters.
