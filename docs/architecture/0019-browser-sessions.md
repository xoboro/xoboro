# ADR 0019: Komga browser sessions

- Status: superseded by ADR 0084
- Date: 2026-07-27

## Context

Komga creates an in-memory session after successful authentication. Browser
clients receive a `KOMGA-SESSION` cookie, while clients that send an
`X-Auth-Token` request header receive the generated session ID in that response
header. Sessions expire after seven days of inactivity.

The login API can convert a header session into a cookie, and logout invalidates
the active session. Authorization changes must revoke existing sessions.

## Decision

- Keep session lifecycle and repository contracts in portable core code.
- Match Komga's in-memory, restart-expiring storage behavior with a
  concurrency-safe server repository.
- Store only SHA-512 token digests in server memory and return the random raw
  token solely through the selected transport.
- Prefer `X-Auth-Token` whenever that request header is present; otherwise use
  the `KOMGA-SESSION` cookie.
- Extend expiry on successful use and revoke sessions on user deletion,
  administrator password reset, or authorization/restriction changes.
- Preserve self-password-change sessions, matching Komga's current-session
  behavior.
- Use HTTP-only, path-rooted, SameSite=Lax cookies and set Secure on HTTPS
  requests.

## Consequences

Browser and header clients can reuse authentication without resending passwords
or API keys. Raw session credentials are not retained by the server.

Remember-me remains a separate signed, long-lived credential. OAuth login will
reuse the same session issuance boundary when implemented.

ADR 0084 replaces restart-expiring in-memory storage after Xoboro moved from
wire-level compatibility to a feature-led security model.
