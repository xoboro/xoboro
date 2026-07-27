# ADR 0009: Source-aware media analysis

- Status: accepted
- Date: 2026-07-27

## Context

Komga analyzes discovered books after inventory and stores media status, profile, ordered
pages, auxiliary files, dimensions, hashes, and stable error codes. Xoboro must preserve
those observable contracts while supporting local files, WebDAV, and future NAS sources
without leaking source-specific paths into the domain.

Opening every archive during inventory is also a major scan bottleneck. Expensive media
work must remain independently scheduled and serialized by book series.

## Decision

- Keep portable media, page, file, dimension, status, and repository models in `core`.
- Put JVM media analyzers behind a source materialization port. An adapter may expose an
  existing local file or download remote content to a managed temporary/cache file.
- Always pass both root and item IDs to materialization. Local access resolves real paths
  and rejects directories, non-file URIs, out-of-root paths, and symlink escapes.
- Analyze ZIP/CBZ entries without extracting them to disk.
- Detect entry content with Apache Tika and order entries with the same case-insensitive
  natural comparator used by Komga 1.25.0.
- Store image entries as one-based pages and all other entries as ordered media files.
- Read image dimensions only when the library setting enables them.
- Preserve Komga media profiles, statuses, and the stable `ERR_1006`, `ERR_1007`, and
  `ERR_1008` archive-analysis codes.
- Replace a book's media, pages, and files atomically. Preserve creation time on
  reanalysis and use explicit 64-bit conversions for SQLite values.

## Consequences

Inventory remains metadata-only and bounded. Local CBZ analysis is usable now, while
WebDAV and NAS adapters can reuse the same analyzer after materialization. Page order and
media state survive restart, and deleting a book cascades through its media subtree.

RAR, PDF, EPUB, page delivery, thumbnails, hashing, and durable worker wiring remain
separate compatibility slices.
