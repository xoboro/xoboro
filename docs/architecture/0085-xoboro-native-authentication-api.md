# ADR 0085: Xoboro-native authentication API

- Status: accepted
- Date: 2026-07-28

## Context

Xoboro needs a first-party HTTP boundary for its future web, Android, and iOS
clients. Reusing the transitional Komga compatibility surface would couple
those clients to another application's DTOs and prevent Xoboro from evolving
its own errors, pagination, authorization, and resource model.

Browser sessions also need CSRF protection, while native clients need a
standard bearer transport and both transports must share persistent,
immediately revocable server sessions. Password endpoints need bounded abuse
without introducing a custom security framework beside Ktor.

## Decision

- Add a dedicated `server:api` module and version its first-party routes below
  `/api/xoboro/v1`.
- Return native JSON resources and a stable `{code, message}` error envelope.
  Compatibility routes remain separate migration and protocol adapters.
- Support an `HttpOnly`, `SameSite=Strict` cookie for the same-origin web
  client and standard bearer authentication for native clients.
- Return a plain token only when creating a bearer session. Persist only its
  digest through the existing session lifecycle.
- Require exact same-origin `Origin` or Fetch Metadata proof before every
  cookie-authenticated mutation, including cookie login and setup.
- Use Ktor's authentication and token-bucket rate-limit plugins. Limit login
  attempts by the verified effective client address and return standard
  `429` and `Retry-After` responses.
- Register native and compatibility authentication providers in one Ktor
  `Authentication` installation so both boundaries use normal framework
  composition.

## Consequences

Web and native clients now have a versioned login and setup contract that does
not depend on Komga. Cookie sessions are protected before credentials or
mutations are processed, and native clients avoid browser-specific CSRF
mechanisms.

Future catalog and administration routes belong in `server:api` and must
reuse this error and authentication boundary. OpenAPI generation, scoped API
keys, native user administration, and a complete authorization review remain
follow-up work.
