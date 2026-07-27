# ADR 0064: Live media differential certification

- Status: accepted
- Date: 2026-07-28

## Context

Catalog comparison identifies each scanned Book independently on Komga and
Xoboro. Media endpoints therefore cannot share one literal request path.
Comparing only catalog DTOs also misses archive streaming, page conversion,
download headers, and WebPub serialization differences.

The first live media comparison exposed divergent WebPub context naming,
empty-value serialization, archive export media types, content-disposition
encoding, and raw-page behavior for non-PDF media.

## Decision

- Allow differential cases to declare named placeholders that occupy complete
  path segments.
- Resolve placeholders independently from environment variables prefixed with
  `KOMGA_REFERENCE_` and `XOBORO_CANDIDATE_`.
- Restrict substituted values to unreserved URI path-segment characters and
  reject missing, partial, undeclared, or malformed placeholders.
- Add a no-body comparison mode for cases where status and media type are the
  stable contract but framework-specific error text is not.
- After the synthetic catalog reaches `READY`, export both generated Book IDs
  and compare page inventory, DiViNa manifest, original archive, original and
  converted pages, thumbnail, and unsupported raw-page behavior.
- Match Komga's WebPub non-empty serialization, `context` property, comic
  acquisition media types, UTF-8 content-disposition format, and private
  revalidation directive.
- Reject raw page requests for non-PDF media before opening a content stream.

## Consequences

Eleven authenticated media cases now exercise real bytes against the
digest-pinned Komga 1.25.0 reference without storing generated IDs or
credentials. Original and converted content is SHA-256 compared, while
manifest exclusions are limited to generated URLs and modification time.
PDF content negotiation, EPUB resources, and range error details remain
separate certification work. Conditional page revalidation is certified in
ADR 0065, selected Book artwork is certified in ADR 0066, and OPDS page and
thumbnail delivery are certified in ADR 0068.
