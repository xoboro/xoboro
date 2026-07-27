# ADR 0066: WebPub and artwork revalidation

- Status: accepted
- Date: 2026-07-27

## Context

ADR 0065 established Komga-compatible validators for page inventory and page
images. Komga's shallow ETag filter also covers WebPub documents, EPUB
resources, selected artwork, individual artwork, and artwork inventories.
Xoboro still transferred these unchanged responses in full.

Artwork additionally has an owner-specific policy. Book and Series artwork use
immediate private revalidation, while the selected Collection and ReadList
artwork responses override that default with a one-hour private cache.

## Decision

- Reuse the exact shallow ETag and conditional-request implementation for
  WebPub manifests, Readium position lists, EPUB resources, selected artwork,
  individual artwork, and artwork inventories.
- Move one-pass `MediaContentStream` materialization into the shared cache
  boundary so page, EPUB, and fallback-artwork routes cannot diverge or reopen
  a source to calculate a validator.
- Attach the analyzed media modification time to EPUB resources and evaluate
  `If-Modified-Since` before opening the archive or remote source.
- Preserve Komga's Content Security Policy on EPUB resource responses.
- Apply `max-age=0, must-revalidate, private` to Book and Series artwork and
  `max-age=3600, private` only to selected Collection and ReadList artwork.
- Extend the live synthetic media suite with selected Book artwork, comparing
  bytes, cache policy, length, and exact ETag against Komga 1.25.0.
- Revalidate manifest and artwork endpoints independently with the validator
  returned by each server, because generated IDs and absolute URLs make
  manifest ETags intentionally server-specific.

## Consequences

The live media differential suite now passes eight authenticated cases.
Manifest and selected-artwork requests return `304` on both servers, and
synthetic integration tests cover conditional manifests, positions, EPUB
resources, selected artwork, individual artwork, inventories, and owner cache
policy.

EPUB response bytes and validators still need a live generated-EPUB
differential fixture. OPDS media, progression documents, and the remaining
non-media REST response surface remain separate shallow-ETag work.
