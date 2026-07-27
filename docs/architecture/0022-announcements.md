# ADR 0022: Cached announcements and durable read state

- Status: accepted
- Date: 2026-07-27

## Context

Komga exposes its public website JSON Feed to administrators and overlays a
per-user read flag. The remote feed is cached for one hour after its latest
access, while read identifiers remain durable in the server database.

## Decision

- Model JSON Feed data and per-user reads behind portable core interfaces.
- Fetch the Komga-compatible feed over HTTPS with bounded connect and request
  timeouts.
- Parse only the supported JSON Feed fields and tolerate unknown extensions.
- Cache successful feeds for one hour after access and serialize refreshes to
  avoid a request stampede.
- Do not cache failures; surface an empty upstream response as 404 and preserve
  Komga's server-error behavior for transport, HTTP, or decoding failures.
- Persist read identifiers idempotently and remove them when the user is
  deleted.
- Require administrator authority for both feed retrieval and read marking.

## Consequences

An unavailable announcement service cannot block startup or affect catalog and
reading operations. Feed content remains remote, while user-specific state is
restart-safe and isolated.
