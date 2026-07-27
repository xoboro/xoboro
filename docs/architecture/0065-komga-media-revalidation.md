# ADR 0065: Komga media revalidation

- Status: accepted
- Date: 2026-07-28

## Context

Komga applies Spring's shallow ETag filter to page inventory and page-image
responses. It also attaches the analyzed media modification time to page and
thumbnail responses. A compatible reader can therefore avoid transferring an
unchanged page, while an `If-Modified-Since` request can be rejected before
the archive or remote source is opened.

Xoboro previously advertised private revalidation without supplying either
validator. Page responses were streamed directly, so calculating a
body-compatible ETag by reopening the source would also have doubled local or
NAS reads.

## Decision

- Generate Komga's exact strong shallow ETag as a quoted `0` prefix followed
  by the lowercase MD5 of the response body.
- Preserve HTTP validator precedence: `If-None-Match` overrides
  `If-Modified-Since`, weak entity tags are accepted for GET revalidation, and
  matching requests return `304 Not Modified`.
- Emit `Last-Modified` only where Komga does: original, converted, and
  thumbnail page responses. Page inventory receives an ETag but no
  modification timestamp.
- Evaluate a valid `If-Modified-Since` before opening page content. For ETag
  generation, read the content stream once into the same bounded-by-JVM body
  representation used by Komga, computing the validator without a second
  source materialization. Initial eager allocation is capped at one MiB.
- Differentially compare ETag presence and exact values for synthetic page
  inventory, original pages, converted pages, and thumbnails.
- Exercise both reference and candidate with their own returned validators in
  CI and require `304` for ETag and modification-time revalidation.

## Consequences

The digest-pinned Komga 1.25.0 reference and Xoboro now return identical ETags
for all four certified synthetic media representations. Date revalidation
avoids archive and NAS access entirely; ETag-only revalidation still performs
one source read, matching Komga's shallow-filter behavior, but suppresses the
response transfer.

Generated archives remain excluded from shallow ETags, matching Komga.
WebPub and artwork revalidation continues in ADR 0066. OPDS media and
PDF-specific representations remain separate certification work.
