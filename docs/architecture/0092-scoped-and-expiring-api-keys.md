# 0092 — Scoped and expiring API keys

## Status

Accepted.

## Context

`docs/feature-coverage.md` carried "add scoped keys, expiration, and protocol-specific transport
review" as the remaining work for API keys, and the authentication-hardening release gate named the
scoped-key policy explicitly.

Before this change an API key was a bearer of its owner's *entire* authority, forever. A key handed to
a Kobo device, a KOReader install, or a script could do anything its owner could — including
administration, if the owner was an administrator — and nothing but deletion ever stopped it. That is
the wrong default for a credential that lives on a device the server does not control.

## Decision

### Scopes are a subset of `UserRole`, not a new vocabulary

A key carries `scopes: Set<UserRole>`. An empty set means "whatever the owner currently holds".

Inventing a separate permission vocabulary was rejected. Roles are already what every interface checks
(`UserRole.PAGE_STREAMING in user.roles`, `UserRole.ADMIN !in principal.user.roles`, and so on). A
second vocabulary would need a mapping from it onto roles, and that mapping would be a place for the
two to disagree.

Library-level and content-restriction scoping are deliberately **not** included. Those already belong
to the account and are enforced as SQL predicates through `CatalogAccess` (ADR 0090); letting a key
also narrow them would create a second, competing definition of what a caller may see. `ApiKey.scope`
therefore only ever touches `roles`.

### The scope is applied by narrowing the caller, once

`ApiKeyLifecycle.authenticate` returns a principal whose `user` has already been narrowed. That single
projection is what makes scoping enforceable: every role check and every `catalogAccess()` call
downstream reads that `User`, so no route, and no protocol adapter, has to know a key was involved. A
route cannot forget to apply a scope it never sees.

This is the same reasoning as ADR 0090, where five copies of the access projection were collapsed into
one. Per-interface scope checks would have recreated exactly the defect that audit had just removed —
which also answers the "protocol-specific transport review" the coverage row asked for: there is
nothing per-transport to review, because no transport participates in the decision.

### A scope can only ever remove capability

`ApiKey.scope` **intersects** the key's scopes with the owner's current roles. A key issued while its
owner was an administrator grants nothing extra after the role is taken away. Storing the scope and
trusting it at face value would have turned a demotion into a no-op for every key issued beforehand.

Creation is stricter than authentication: a scope naming a role the owner does not hold is rejected
with `400 invalid_request`. At creation the caller is stating an intent, and an intent that cannot be
satisfied is a mistake worth reporting. At authentication the same mismatch is expected — roles get
reduced — and silent narrowing is the correct response.

### Expiry is absolute and decided at presentation

`expiresAtMillis` is an epoch instant, not a duration, so a stored key's lifetime cannot grow by being
read later. `hasExpired` is inclusive of the instant it names: a key said to expire at *t* must not
still work at *t*.

The check runs in `authenticate`, not in a scheduled sweep. A sweep would make expiry depend on the
job having run — a key whose expiry passed an hour ago would still authenticate until the next tick.
Expired rows are deliberately **kept and still listed**, so an owner can see which key stopped working
and delete it deliberately rather than watching keys vanish.

### The compat surface is unchanged

`/api/v1/users/me/api-keys` has no scope or expiry field and creates unscoped, non-expiring keys.
Komga's contract has no such concept and compat is transitional (ADR 0082); adding fields there would
invent an API no Komga client expects. Callers who want a scoped key use the native surface.

## Consequences

- Scopes live in `user_api_key_scope`, a child table, rather than in a delimited column: a scope value
  is a row that can be constrained and cascaded, not a substring. Insert of a key and its scopes is
  one transaction — a key that existed without its scopes would authorize *more* than intended, which
  is the wrong direction for a partial write to fail.
- An unrecognised scope name read back from the database is **dropped**, not preserved. Dropping
  narrows, which is the safe direction if a future release removes a role a stored key still names.
- Migration V28 is additive and backward compatible. Existing rows have no scope rows and a null
  expiry, which is exactly "as capable as the owner, never expires" — their behaviour is unchanged, and
  a test asserts a fresh unscoped key is indistinguishable from a pre-migration row.
- The native `400` for a rejected creation now reports the lifecycle's own message. A fixed string
  would have told a caller who sent a past expiry that their comment was blank.
- Verified by mutation: never writing scope rows, and dropping the expiry on read, each fail the
  round-trip test. The round trip asserts whole-object equality on a reopened database, which is what
  catches a swapped bind or a narrowed integer.
