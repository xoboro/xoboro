# ADR 0069: Global Komga shallow ETag filter

- Status: accepted
- Date: 2026-07-27

## Context

Komga registers Spring's `ShallowEtagHeaderFilter` for `/api/*`, `/opds/*`,
and `/kobo/*`. Only original and generated download routes are excluded. This
means serialized JSON, XML, text, and byte-array responses share one exact
post-serialization validator contract.

Xoboro had exact validators on performance-sensitive media routes, but most
catalog, management, OPDS feed, and Kobo responses still omitted them.
Adding validators route by route would duplicate policy and could hash a
pre-serialization object rather than the actual wire bytes.

## Decision

- Intercept Ktor's send pipeline after content negotiation has rendered a
  response and before content encoding.
- Apply the filter only to successful GET byte-array content under the three
  Komga protocol prefixes, including deployments mounted below a context path.
- Hash the exact outgoing bytes with the existing Spring-compatible
  `"0" + lowercase MD5` entity tag.
- Preserve existing validators and cache policies from media routes.
- Skip `no-store` responses and Komga's five download path patterns.
- Return `304` for wildcard, strong, weak, or comma-separated matching
  `If-None-Match` values without reserializing the response a second time.
- Apply Komga's private immediate-revalidation policy when a filtered response
  has no route-specific cache policy.

## Consequences

The compatibility boundary now covers ordinary REST, OPDS, and Kobo responses
centrally without changing domain handlers. Streaming and channel responses
remain unbuffered; the excluded download paths keep their dedicated range and
archive delivery behavior.

Digest-pinned live checks compare exact cache policy, length, and ETag for the
anonymous claim and OAuth-provider responses, then independently verify `304`
on both servers.
