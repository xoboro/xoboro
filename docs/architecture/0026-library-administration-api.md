# ADR 0026: Library administration and scan boundary

- Status: accepted
- Date: 2026-07-27

## Context

Library administration is the catalog entry point. It must preserve Komga's
local-path wire contract while Xoboro's domain supports source-aware libraries
and a shared `MediaItem` hierarchy for comics, novels, books, video, and audio.
HTTP requests must not depend directly on the durable task implementation.

## Decision

- Keep source identity and canonical URI roots in the portable domain. Convert
  Komga filesystem paths to local source URIs only at the compatibility
  boundary and convert them back only for administrator responses.
- Route create, update, and delete through one validated administration
  lifecycle. Preserve creation identity and timestamps during updates.
- Reject missing, non-directory, duplicate-name, and overlapping local roots
  before persistence.
- Preserve Komga's complete library settings DTO, including explicit-null
  clearing for directory exclusions and the one-shot directory.
- Filter list and detail reads through the authenticated user's library grants.
  Return an empty root to non-administrators and reserve all mutations for
  administrators.
- Publish lifecycle changes to periodic-scan scheduling. Additions schedule,
  scan-interval updates reschedule, and deletion cancels.
- Expose manual normal and deep scans through a portable
  `LibraryScanRequester`; the production adapter emits a highest-priority
  durable scan task and the HTTP handler immediately returns `202 Accepted`.
- Keep the compatibility API local-only. Future Xoboro-native APIs may create
  WebDAV and other NAS-backed libraries without weakening Komga wire behavior.

## Consequences

The Komga library surface is operational without coupling Ktor to the task
module. Every media subtype can reuse the same library ownership, access,
source, scheduling, and scan boundaries. Dedicated hash, repair, conversion,
and metadata-refresh jobs remain separate compatibility work; until those
handlers land, maintenance-setting transitions request a deep scan so catalog
state is not silently left stale.
