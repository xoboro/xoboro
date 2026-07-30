# 0100 — Native duplicate-page surface, without removal

## Status

Accepted. Removal is deliberately out of scope; see Decision.

## Context

Duplicate-page detection existed and stored its results. Nothing exposed them outside the
Komga-compatible surface, so a native-only deployment could not see or act on them — the coverage row
read "Native API/UI and optional automatic policy execution".

Duplicate pages are almost always scanner credits, advertisements, or an "end of volume" filler that a
releaser stamps into every archive of a release group.

## Decision

### Removal is not implemented, and that is the decision rather than the remainder

Nothing in Xoboro executes `DELETE_AUTO` or `DELETE_MANUAL`, and this change does not add it.

Removing a duplicate page means **rewriting an archive on the operator's disk**. That is destructive,
irreversible with respect to their own files, and — with `DELETE_AUTO` — it would happen unattended
during a scan. Building that unprompted is not a call to make on someone else's library.

So the surface records decisions. The two delete actions are accepted and stored as an operator's
stated intent, and both the API doc and the response say plainly that nothing performs the removal.

`deleteCount` is returned even though it is always `0`. It is the stored value, and an administrator
reading it should see the field now rather than have it appear later and change what a response means.

### The delete actions are not rejected, because `IGNORE` alone makes the surface useful

The alternative was to accept only `IGNORE` until removal exists, so the catalog could never hold an
intent nothing honours. Rejected for two reasons:

- The Komga-compatible surface already accepts all three, so the data can contain them regardless.
  Rejecting them natively would make the two surfaces disagree about what is storable without making
  the data any more truthful.
- `IGNORE` has a **real** effect: `findUnknown` excludes any hash with a recorded decision, so ignoring
  a hash removes it from the candidate list permanently. Reviewing a library and dismissing the
  false positives is worthwhile on its own.

### Administrator-only, regardless of library grants

A duplicate-page listing reports file names and sizes from every library at once. A caller with a grant
on one library must not learn another library's file layout from it, and the hash is not scoped to a
library in any way that would let this be filtered instead. So the gate is the role, not the grant.

### Paging goes through `CatalogPageRequest`

Rather than validating `page`/`size` inline, the route constructs the domain type and translates its
`IllegalArgumentException` into `400`. The domain type's bounds stay the single definition of a valid
page, and an out-of-range size is a client error rather than a 500.

## Consequences

- A native-only deployment can review duplicate pages, see which media items carry each one, and
  dismiss false positives.
- The coverage row keeps three named remainders: the removal executor, an automatic policy that runs
  it, and the administrator UI. It no longer implies the whole feature is missing.
- Verified by mutation: dropping the administrator gate fails the non-administrator test, and making an
  empty `action` filter mean an empty set — rather than every action — fails the filter test.
- The OpenAPI drift test caught the four new routes before review did, which is what it is for.
