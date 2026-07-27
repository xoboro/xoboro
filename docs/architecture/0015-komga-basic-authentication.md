# ADR 0015: Komga Basic authentication

- Status: accepted
- Date: 2026-07-27

## Context

Every non-public Komga API eventually needs a shared authentication boundary. Basic
authentication is the first supported mechanism because it is stateless, is used by
existing API clients, and can verify the BCrypt identities already stored by Xoboro.

Komga 1.25.0 challenges protected REST requests with the `Realm` Basic realm, treats
email lookup case-insensitively, and exposes the authenticated principal at
`GET /api/v2/users/me`. The claim API remains public.

## Decision

- Configure a named Ktor Basic provider in the Komga compatibility module.
- Authenticate through the portable `UserLifecycle`; the HTTP adapter never reads
  password hashes or queries persistence directly.
- Use the `Realm` challenge value expected by Komga clients.
- Place authenticated users in a Komga-specific principal and map that principal to the
  existing password-free user wire DTO.
- Protect `/api/v2/users/me` while keeping `/api/v1/claim` outside the authenticated
  route group.
- Return `401 Unauthorized` for missing, malformed, unknown-user, and wrong-password
  credentials without revealing which credential failed.

## Consequences

Komga API clients can validate credentials and retrieve their current identity with
HTTP Basic authentication. Future protected REST routes can join the same authenticated
group and later accept session or API-key principals without changing domain code.

Browser sessions, remember-me cookie issuance, API keys, authentication activity,
authorization by role, and the remaining user administration endpoints are separate
compatibility increments.
