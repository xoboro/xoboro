# ADR 0071: Configured Komga CORS

- Status: accepted
- Date: 2026-07-27

## Context

Komga enables cross-origin protocol access only when
`KOMGA_CORS_ALLOWEDORIGINS` binds at least one exact origin. Its Spring CORS
processor accepts credentials, exposes download and session headers, permits
every Java HTTP method, reflects requested preflight headers, and caches
preflight decisions for 1,800 seconds. Once configured, an unlisted origin is
rejected even for a simple request.

Xoboro already emitted Komga's stable CORS `Vary` headers but did not process
configured cross-origin requests.

## Decision

- Parse the Komga environment variable as a deduplicated comma-separated set.
- Reject malformed values at startup. Each value must be `null` or an absolute
  HTTP or HTTPS origin without user information, path, query, or fragment.
- Apply CORS only to the context-aware routes covered by Komga's three
  security chains. Explicitly exclude the native API mounted at
  `/api/xoboro/v1` (see `server/api`'s `XOBORO_API_PREFIX`): that prefix sits
  under the same `/api` branch the compat matcher searches for, so without an
  explicit exclusion an operator's `KOMGA_CORS_ALLOWEDORIGINS` allowlist would
  also gain credentialed cross-origin access to native admin surfaces such as
  `/api/xoboro/v1/users` and `/api/xoboro/v1/server-settings`.
- Echo an allowed origin, permit credentials, and expose
  `Content-Disposition` plus `X-Auth-Token`.
- Answer valid preflight requests with Komga's method list, reflected request
  headers, and 1,800-second maximum age.
- Return `403 Invalid CORS request` for an unlisted origin or method while
  preserving the protocol security headers.
- Keep an unset origin list behavior-neutral so same-origin deployments do not
  gain cross-origin access.
- Certify allowed, rejected, and preflight requests against the digest-pinned
  Komga 1.25.0 image.

## Consequences

Existing Komga deployments can carry their CORS environment setting into
Xoboro without changing browser clients. The configuration remains
fail-closed and cannot inject response headers.

The anonymous live differential suite also covers authentication and protocol
failures in addition to the six CORS and public-contract cases. Wildcard origins
are rejected at startup because they cannot be combined with Komga's required
credential policy.

`installKomgaSecurityHeaders` shares the same servlet-path matcher as CORS, so
excluding the native prefix also stops native responses from receiving
Komga's `X-Frame-Options`, `X-Content-Type-Options`, and `Vary` headers.
Whether the native API should carry its own hardening headers is a separate
decision, tracked outside this ADR.
