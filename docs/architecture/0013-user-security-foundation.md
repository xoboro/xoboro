# ADR 0013: User security foundation

- Status: accepted
- Date: 2026-07-27

## Context

Komga-compatible authentication requires durable users before HTTP security can be
enabled. User identity also affects library visibility and age or sharing-label
filters throughout the catalog. A partial implementation that stores raw credentials,
allows two concurrent initial claims, or applies different restriction rules would
make later API compatibility unsafe.

Komga 1.25.0 stores BCrypt password hashes with the Spring Security default strength,
compares email addresses without case sensitivity, grants the initial administrator
every defined role, and treats exclusion labels as stronger than allow labels.

## Decision

- Keep the portable user, role, library-access, and content-restriction model in
  `core:domain`.
- Keep password hashing behind the `PasswordHasher` application port. Domain and
  persistence code only receive hashes and never retain raw passwords.
- Use Spring Security's BCrypt encoder with strength 10 so existing Komga hashes use
  the same format and verification behavior.
- Store users, roles, shared libraries, and normalized sharing labels in relational
  SQLite tables with foreign keys and cascading cleanup.
- Enforce email uniqueness case-insensitively in SQLite, not only in application code.
- Claim the first administrator with one conditional database insert. Concurrent
  callers cannot both claim an empty server.
- Match Komga's content rules: allow-only age and labels combine with OR, while any
  matching age or label exclusion denies access.

## Consequences

The server now has a restart-safe identity model and a compatible credential primitive
for the claim and login APIs. The public claim endpoint, Basic authentication, browser
sessions, API keys, user administration endpoints, authorization middleware, and
authentication activity remain separate compatibility increments.

The Spring Security crypto module is used only as a focused hashing dependency. Xoboro
does not adopt Spring's HTTP or dependency-injection runtime.
