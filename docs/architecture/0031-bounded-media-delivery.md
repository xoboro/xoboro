# ADR 0031: Bounded source-aware media delivery

## Context

Page readers and offline clients need original pages, converted images,
thumbnails, and source-file downloads. A source can be local today and remote
later, so HTTP handlers must not assume a filesystem path or retain an entire
archive in memory. Delivery must also preserve per-user catalog restrictions
and Komga's dedicated streaming and download roles.

## Decision

- Define a portable `BookContentAccess` port beside the shared media-item
  application contracts. Komga routes are adapters over this port rather than
  owners of archive logic.
- Resolve a book through the access-filtered SQL catalog before opening its
  source.
- Materialize through the library's source adapter and open only the exact ZIP
  entry stored by analysis. Keep the archive and materialization alive only for
  the response stream, then close both even when response copying fails.
- Stream original pages and book files with an 8 KiB copy buffer. Original-file
  downloads support a single HTTP byte range without buffering the file.
- Decode only the requested page for JPEG/PNG conversion. Inspect image
  dimensions before decoding, reject images above the safety pixel limit, and
  bound page thumbnails to 300 pixels on their largest dimension.
- Enforce `PAGE_STREAMING` for full page bytes and `FILE_DOWNLOAD` for original
  files. Page metadata and thumbnails remain authenticated and content
  restricted.

## Consequences

Large archives and remote source materializations do not become response-sized
heap allocations. The same delivery port can later route PDF, EPUB, WebDAV, and
other NAS adapters while preserving `Library` and `MediaItem` identity.
Converted pages are intentionally page-sized allocations; a durable thumbnail
cache and conversion concurrency budget remain future work.
