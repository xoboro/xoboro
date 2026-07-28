# ADR 0084: Persistent user sessions

- Status: accepted
- Date: 2026-07-28

## Context

Restart-expiring browser sessions reproduce Komga's storage behavior but are a poor
server default. Routine upgrades and host restarts log out every device, while a
read-then-write expiry extension can also move a session backwards when concurrent
requests finish out of order.

Xoboro needs restart-safe authentication without retaining bearer credentials in its
database. Revocation, inactivity expiry, and authorization changes must remain
immediate.

## Decision

- Persist sessions in SQLite with a foreign key to their user and indexes for owner
  revocation and expiry cleanup.
- Store only the SHA-512 token digest. Return the random bearer token only to the
  client that created the session.
- Extend active sessions with one conditional SQL update. Access and expiry
  timestamps use their current or requested maximum so concurrent requests cannot
  shorten a session.
- Treat the conditional update as the authentication commit point. A session deleted
  or expired after its initial lookup cannot authenticate.
- Delete expired sessions at server startup, on expired authentication attempts, and
  through the lifecycle cleanup operation.
- Cascade-delete sessions with users and revoke all sessions after administrator
  password, role, or restriction changes.

## Consequences

Authenticated clients survive clean server restarts and deployments. Database copies
do not contain reusable session tokens, and concurrent access cannot resurrect an
expired or revoked session or reduce its inactivity window.

Cookie CSRF protection and login throttling remain separate HTTP-boundary work.
