# ADR 0016: Komga user administration

- Status: accepted
- Date: 2026-07-27

## Context

A Komga replacement needs more than credential verification: administrators must
create and manage users, users must rotate their own passwords, and every change must
preserve library and content restrictions. Patch requests also distinguish an omitted
field from an explicit null or empty value.

Komga 1.25.0 exposes these operations under `/api/v2/users`. Administrator list,
create, update, and delete operations require the `ADMIN` role. Administrators cannot
update or delete themselves through target-user routes. A user can update their own
password, while administrators can reset another user's password.

## Decision

- Extend the portable user lifecycle with find, update, password-update, and delete
  operations; raw passwords remain confined to the hashing port.
- Implement the Komga v2 user request DTOs and routes in the compatibility adapter.
- Enforce administrator and self-service rules against the authenticated principal
  before invoking lifecycle operations.
- Ignore unknown role names, matching Komga's role conversion behavior.
- Resolve requested shared-library IDs against durable libraries and silently discard
  unknown IDs.
- Parse user patches as JSON objects so omitted properties preserve their current
  values while explicit null or empty restriction fields remove those restrictions.
- Keep exclusion-over-allow normalization in the domain model rather than duplicating
  it in the HTTP adapter.

## Consequences

Administrators can manage durable users and all supported restrictions through the
same v2 paths and statuses as Komga. Password changes invalidate the previous Basic
credential immediately, and no response includes password material.

Exact global error-envelope parity, demo-mode mutation blocking, authentication
activity, API keys, browser sessions, and OAuth2-linked user behavior remain separate
compatibility increments.
