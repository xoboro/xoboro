# ADR 0039: Source-aware file lifecycle

- Status: accepted
- Date: 2026-07-27

## Context

Komga exposes local filesystem discovery, transient import analysis, file import,
file deletion, and multi-book downloads. Xoboro must preserve those contracts
without making the catalog or compatibility adapter depend on local filesystem
paths. Future WebDAV, object storage, Video, and Audio sources need the same
mutation boundary.

## Decision

- Keep import and deletion behind a source-keyed `SourceMutationAccess`
  contract. The catalog durable-task layer resolves Library and Series source
  identities; only the local adapter interprets `file:` URIs.
- Validate canonical roots and leaf names before every mutation. Imports use a
  temporary sibling followed by an atomic move, and upgrades replace the old
  file only after the new content is durable.
- Run import and deletion as deduplicated, highest-priority durable tasks, then
  request a library scan. Retries are idempotent after partial deletion.
- Stream Series and ReadList ZIPs directly from source materializations rather
  than buffering complete archives.
- Keep transient scans outside the catalog. Reject paths overlapping configured
  local Library roots, analyze only supported CBZ/ZIP, EPUB, and PDF files, and
  retain at most 1,000 transient entries in an access-expiring one-hour memory
  cache for the import session.
- Treat Komga filesystem paths and DTOs as compatibility-boundary details.

## Consequences

Filesystem mutation no longer leaks into the portable catalog model. New source
providers can implement copy, move, link-equivalent, and deletion semantics
without changing REST routes or task payloads. Large downloads stay bounded,
and transient previews reuse the production analyzers without polluting
persistent catalog state.
