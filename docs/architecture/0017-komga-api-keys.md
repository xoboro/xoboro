# ADR 0017: Komga API keys

- Status: accepted
- Date: 2026-07-27

## Context

Native, automation, and NAS clients should authenticate without retaining a user's
password. Komga provides user-owned API keys through the `X-API-Key` header and only
reveals the plain token in the creation response.

Komga 1.25.0 generates a dashless UUID v4 token, stores a deterministic lowercase
SHA-512 hex digest, retries token collisions ten times, rejects duplicate comments
case-insensitively per user, and masks keys as six asterisks in list responses.

## Decision

- Keep API-key identity and repository contracts in the portable core, separate from
  password credentials.
- Generate 32-character dashless UUID v4 tokens and TSID-256 key IDs in the server
  runtime.
- Encode stored and lookup tokens with the same lowercase SHA-512 representation as
  Komga 1.25.0. Never persist the plain token.
- Return the plain token only from successful creation; list responses always contain
  `******`.
- Persist keys in a user-owned SQLite table with case-insensitive per-user comment
  uniqueness, globally unique hashes, and cascading deletion.
- Accept `X-API-Key` as an alternative to Basic authentication for protected Komga
  routes.
- Preserve the current-user create, list, and delete endpoints and duplicate-comment
  error code.

## Consequences

Clients can authenticate without a password, existing Komga SHA-512 key material can
be imported later, and deleting a key invalidates it immediately. Database snapshots do
not contain recoverable plain API keys.

Authentication activity, demo-mode mutation restrictions, Kobo URL keys, KOReader
headers, session credentials, and importer tooling remain separate increments.
