# ADR 0044: Durable Kobo synchronization and bounded KEPUB conversion

- Status: accepted
- Date: 2026-07-27

## Context

Kobo devices authenticate with an API key embedded in the store URL and pull
incremental EPUB, progress, and shelf updates through a proprietary JSON
protocol. A sync can span multiple requests and server restarts. Rebuilding a
large result set on every continuation would duplicate entries, while holding
the state in memory would lose progress on restart. Reflowable EPUBs also need
optional KEPUB conversion for reliable device progress.

## Decision

- Authenticate the URL token through the shared API-key lifecycle and require
  the `KOBO_SYNC` role. File delivery additionally requires `FILE_DOWNLOAD`.
- Capture access-filtered READY EPUB item, progress, and read-list revisions in
  normalized SQLite snapshot tables.
- Persist a cursor on the current sync point. Recompute a deterministic delta
  from the two immutable snapshots, advance the cursor transactionally, and
  use Komga-compatible sync-token and continuation headers.
- Expose Kobo metadata, entitlements, reading state, shelves, initialization,
  thumbnails, downloads, and no-proxy catch-all behavior through the shared
  catalog, artwork, media, and progress boundaries.
- Run the configured `kepubify` executable with a bounded timeout and streamed
  input. Cache converted files by Book revision, atomically publish output, and
  delete superseded revisions instead of retaining unbounded in-memory bytes.

## Consequences

Kobo synchronization survives process restarts and scales independently of a
single HTTP response. Progress written by a device is immediately visible to
WebPub, OPDS, and other clients. Existing KEPUB files stream directly; normal
EPUB files advertise KEPUB only when conversion is configured. Kobo Store proxy
merging and differential device fixtures remain required before exact
compatibility certification.
