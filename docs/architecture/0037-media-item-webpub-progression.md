# ADR 0037: Media-item WebPub and reading progression

- Status: accepted
- Date: 2026-07-27

## Context

Komga exposes Readium WebPub manifests and durable progression records through
Book-shaped routes. Xoboro's stable domain hierarchy is `Library` to
`MediaItem`, specialized as Comic, Novel, Book, Video, and Audio. Reading state
must remain reusable by native clients and future text media instead of making
the Komga transport model the domain root.

## Decision

- Own page-based reading state by `MediaItemId` and user identity. Persist the
  Readium locator, client device, client modification time, and server update
  time with the existing progress row.
- Reject progression updates whose client timestamp is not newer than the
  stored value. Validate locator positions against analyzed page bounds before
  writing.
- Build DiViNa manifests from analyzed Comic pages and normalized catalog
  metadata. Keep absolute Komga Book URLs and Readium JSON DTOs exclusively in
  the compatibility adapter.
- Map series reading direction to Readium progression without changing the
  portable domain vocabulary.
- Add EPUB and PDF manifests, resources, and positions when those analyzers are
  implemented; do not represent archive pages as fake EPUB resources.

## Consequences

Comic clients can resume from an exact interoperable locator now, while native
Xoboro clients can share the same durable state through a future media-neutral
API. Novel and Book implementations can add text locators without changing
Library ownership. Video and Audio will use the same per-user media progress
boundary with timeline-specific payloads rather than inheriting Comic pages.
