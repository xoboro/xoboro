# ADR 0070: Protocol response headers and bounded image revalidation

- Status: accepted
- Date: 2026-07-27

## Context

Komga applies Spring Security response headers through three filter chains.
REST, OPDS, SSE, OAuth, and actuator routes permit same-origin framing for the
EPUB reader. Kobo and KOReader routes retain Spring's deny policy. All three
chains also emit content sniffing, legacy XSS, and CORS variance headers.

Komga's shallow ETag filter sees controller byte arrays. Xoboro still emitted
transient pages, duplicate-page thumbnails, and Kobo covers as Ktor streaming
content, so the global post-serialization filter could not validate them.
Download routes must remain streamed and excluded from shallow hashing.

## Decision

- Add one context-path-aware response-header interceptor for Komga protocol
  routes.
- Emit `X-Content-Type-Options: nosniff`, `X-XSS-Protection: 0`, and the exact
  `X-Frame-Options` policy selected by the matching Komga security chain.
- Emit the three Spring CORS `Vary` values in stable order.
- Buffer only image responses that Komga already materializes as byte arrays:
  transient pages, duplicate-page thumbnails, and Kobo covers.
- Reuse the exact `"0" + lowercase MD5` ETag and weak/wildcard/list validator
  matcher for those bounded images.
- Keep Book, Series, ReadList, archive, and Kobo EPUB downloads on their
  existing unbuffered delivery paths.
- Compare the security and variance headers against the digest-pinned Komga
  anonymous baseline.

## Consequences

Reader iframes retain the same-origin capability required by Komga, while sync
protocols cannot be framed. Browser and proxy caches now receive observable
Komga-compatible security and variance metadata.

The remaining download paths do not acquire a whole-file memory copy. Bounded
administrative and sync images gain exact conditional revalidation and avoid
retransmission after the first request.
