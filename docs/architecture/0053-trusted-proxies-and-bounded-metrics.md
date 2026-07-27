# ADR 0053: Trusted proxies and bounded operational metrics

## Status

Accepted.

## Context

Xoboro commonly runs behind TLS termination and a context-path reverse proxy.
Blindly accepting forwarding headers lets a direct client spoof its address,
scheme, and host, which affects authentication history, secure cookies,
absolute links, and OAuth callback validation. Operational metrics can also
leak catalog paths or exhaust a monitoring system when labels are derived from
raw request URLs or identifiers.

## Decision

- Reject RFC `Forwarded` and all recognized `X-Forwarded-*` headers unless the
  physical peer is in the explicit `XOBORO_TRUSTED_PROXIES` allowlist.
- Leave forwarding support disabled when the allowlist is empty. For an
  accepted proxy, remove known trailing proxies and use Ktor's parsed origin
  for security decisions and external URL construction.
- Enable `/metrics` only when a dedicated bearer token of at least 32
  characters is configured. The route follows the effective context path and
  does not reuse user sessions or API keys.
- Record HTTP method in a fixed allowlist and status only as a bounded class.
  Export counters and duration summaries plus active requests, readiness,
  durable queue size, worker count, and uptime.
- Never place paths, query strings, user/catalog identifiers, errors, tokens,
  or forwarding values in metric labels or values.
- Emit one JSON object per log event with stable service, timestamp, level,
  logger, thread, message, and exception fields. HTTP access messages include
  method, path, and status but deliberately omit query strings so login codes
  and other credentials cannot enter routine request logs.

## Consequences

A proxy deployment must configure the address observed by Xoboro, not a public
client range. Misconfigured or direct forwarding attempts fail closed with a
stable client error. Metrics remain cheap and bounded for long-running
instances, while operators retain enough signals for availability, latency,
backlog, and worker-capacity alerts.
