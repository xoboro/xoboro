# ADR 0014: Komga claim API boundary

- Status: accepted
- Date: 2026-07-27

## Context

The first externally observable Komga compatibility surface is the anonymous server
claim endpoint. It establishes the JSON conventions, validation behavior, identity
format, and module boundary that later REST controllers will share.

Komga 1.25.0 exposes `GET /api/v1/claim` without authentication and accepts the initial
administrator credentials from `X-Komga-Email` and `X-Komga-Password` headers on
`POST /api/v1/claim`. The returned user representation includes the implicit `USER`
authority, excludes the password, and omits null fields.

## Decision

- Place Komga-specific routes and wire DTOs in `compatibility:komga-api`; they do not
  leak into portable domain or application modules.
- Preserve the claim path, request headers, success status, validation status, user
  fields, implicit `USER` role, and null-field omission.
- Reuse the atomic application claim operation instead of relying on a count-then-write
  sequence at the HTTP boundary.
- Generate persisted user IDs with the same TSID-256 library and representation used by
  Komga 1.25.0.
- Assemble the adapter only in the production server runtime, where SQLite persistence
  and adaptive password hashing are available.

## Consequences

An unclaimed Xoboro server can be claimed by existing Komga-aware clients through the
same endpoint, and the result survives restart. The compatibility module can grow
controller by controller without coupling Ktor types to the portable core.

Basic authentication, sessions, API keys, user administration, automated initial-user
configuration, and exact global error-envelope parity remain separate increments.
