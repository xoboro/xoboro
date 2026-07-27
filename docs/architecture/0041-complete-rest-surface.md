# ADR 0041: Complete Komga REST surface

- Status: accepted
- Date: 2026-07-27

## Context

The final uncovered Komga 1.25 REST operations concern server information,
fonts, releases, bulk poster regeneration, and duplicate-page deletion.
Poster generation and archive rewriting are potentially expensive or
destructive and must not run in request threads.

## Decision

- Expose all 165 pinned OpenAPI operations through the compatibility boundary.
  Keep Xoboro identity in the actuator payload while retaining Komga's response
  shape.
- Discover supported font files once at startup from family subdirectories.
  Serve exact discovered names only, generate deterministic escaped
  `@font-face` CSS, and never interpret request values as paths.
- Retrieve Xoboro GitHub releases with strict timeouts, unknown-field-tolerant
  decoding, single-flight refresh, and one-hour caching.
- Enqueue one low-priority generated-artwork task per eligible Book. Generate
  from the first page with bounded JPEG processing and atomically replace only
  the previous generated candidate, preserving selected user artwork.
- Enqueue duplicate-page deletion per Book at highest priority. Rewrite local
  ZIP/CBZ files into a temporary sibling, atomically replace the source only
  after a successful close, increment the durable deletion counter, and request
  a high-priority library reconciliation.
- Keep both maintenance flows on the source-neutral application contract so
  future WebDAV/object-store adapters can implement equivalent atomic mutation.

## Consequences

REST inventory completeness no longer implies synchronous heavy work. Request
latency remains bounded, generated poster state survives restart, user uploads
retain precedence, and failed archive rewrites leave the original source
untouched. REST coverage is still `PARTIAL` until differential behavior and
schema tests certify each operation.
