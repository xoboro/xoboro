# ADR 0021: Namespaced global and per-user client settings

- Status: accepted
- Date: 2026-07-27

## Context

Komga clients store arbitrary string preferences under lowercase namespaced
keys. Global values can be public or authentication-only, while per-user values
must be isolated and removed with their owner.

## Decision

- Keep the setting contract and validation in portable core modules.
- Persist global and per-user values in separate SQLite tables.
- Enforce the Komga lowercase namespace key grammar before a batch write.
- Return only explicitly public global values to anonymous callers and all
  global values to authenticated callers.
- Require administrator authority for global writes and deletes.
- Scope user reads, writes, and deletes to the authenticated user.
- Execute multi-value upserts transactionally and cascade user settings on
  account deletion.

## Consequences

Web and native clients can share server-controlled configuration while retaining
private per-user preferences. Arbitrary string values preserve compatibility
with clients that embed JSON in the value.
