# ADR 0020: Komga-compatible remember-me authentication

- Status: accepted
- Date: 2026-07-27

## Context

Komga uses Spring Security's stateless token-based remember-me service. A
successful password or API-key request carrying `remember-me=true` receives a
long-lived `komga-remember-me` cookie. That cookie can restore authentication
after the in-memory session expires or the server restarts.

The signed token includes the user email, expiry time, SHA-256 algorithm name,
and a digest over the email, expiry, current password hash, and a server key.
Password changes therefore invalidate previously issued tokens.

## Decision

- Reproduce Spring Security 6.5's four-field token format and SHA-256 signature
  so existing Komga browser credentials remain structurally compatible.
- Persist the 32-character server key in the generic `server_setting` table and
  create it atomically on first startup.
- Default to Komga's 365-day validity and cookie max age.
- Authenticate remember-me after Basic, API-key, and active-session providers.
- Issue a normal seven-day session after remember-me authentication.
- Clear both session and remember-me cookies on either logout endpoint.
- Record successful restored logins with the `RememberMe` authentication source.
- Use HTTP-only, path-rooted, SameSite=Lax cookies and set Secure for HTTPS.

## Consequences

Browser login survives server restarts without persisting raw credentials or
server-side remember-me tokens. Rotating the server key or changing a password
invalidates outstanding tokens. The settings API will expose key rotation and
duration changes through the same persisted setting boundary.
