# ADR 0107: Native media fast revalidation

- Status: accepted
- Date: 2026-09-08

## Context

ADRs 0065 and 0066 selected Komga's body-derived strong ETag for both compatibility
and Xoboro-native media. On the native page and artwork routes this requires opening
and fully buffering content before a conditional request can return `304`. A reader
grid or page revisit therefore repeats SQLite blob reads, archive/NAS access, hashing,
and whole-body allocation even though Xoboro controls both ends of the native API.

The original interoperability reason applies to Komga-compatible endpoints, not to
Xoboro's native API. Native clients need a stable validator for the representation;
they do not need the same validator bytes as Komga.

## Decision

- Xoboro-native page, EPUB-resource, and artwork responses use weak validators derived
  from the stored source/media/artwork version and representation parameters.
- Authorization and range/status validation still occur before conditional headers are
  honored.
- A matching `If-None-Match` or `If-Modified-Since` returns before opening content.
- A cache miss streams the existing `MediaContentStream` directly to the response and
  does not construct another complete byte array.
- Komga-compatible endpoints retain the exact validator behavior certified by ADRs
  0065 and 0066.

## Consequences

Native validators are deliberately not byte-identical to Komga and may remain equal
across byte-level changes that preserve the recorded representation version, which is
why they are weak. Normal scans and artwork mutations update the included version data.
Conditional reader requests no longer open an archive or artwork blob, and successful
responses use memory proportional to the copy buffer rather than the body size.

This decision supersedes ADRs 0065 and 0066 only for Xoboro-native byte-delivery routes.
