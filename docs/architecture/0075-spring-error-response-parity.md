# ADR 0075: Match Spring Authentication and Error Responses

- Status: accepted
- Date: 2026-07-28

## Context

Komga clients observe more than an HTTP status when authentication or request
handling fails. REST and OPDS v1 return Spring's timestamped JSON error
envelope and a quoted Basic realm. OPDS v2 instead returns its authentication
document, a discovery `Link`, and the same Basic challenge. Missing resources
also use the Spring envelope with `404 NOT_FOUND`.

Ktor's built-in Basic provider emitted an unquoted realm with a UTF-8
parameter and an empty body. Explicit not-found responses were also empty, and
request deserialization failures escaped as internal-server errors.

## Decision

- Replace the built-in Basic provider with a bounded parser and a shared
  challenge responder while preserving case-insensitive usernames, activity
  recording, sessions, remember-me, and first-successful provider semantics.
- Emit `Basic realm="Realm"` and a timestamped Spring-compatible JSON envelope
  for REST and OPDS v1 authentication failures.
- Emit Komga's full OPDS v2 authentication document, discovery `Link`, media
  type, and quoted Basic challenge when an OPDS v2 resource requires login.
- Add a scoped status-page layer for `/api/**` and `/opds/**`. It renders
  explicit 403/404 statuses and deserialization failures without changing
  health, metrics, Kobo, or KOReader contracts.
- Keep validation responses as their dedicated `violations` document instead
  of replacing every 400 response globally.
- Differentially compare anonymous, invalid-credential, missing-resource, and
  malformed-JSON behavior against the digest-pinned Komga 1.25.0 image.

## Consequences

REST, WebPub, OPDS v1, and OPDS v2 clients now receive the same status, error
shape, path, media type, Basic realm, and protocol discovery information as
Komga. Dynamic timestamps and environment-specific OPDS absolute URLs are the
only ignored fields in those live comparisons.

Malformed JSON is classified as 400 and carries the Spring envelope. Its
framework-specific diagnostic text is ignored by the differential suite
because Jackson and kotlinx.serialization describe the same syntax failure
differently.
