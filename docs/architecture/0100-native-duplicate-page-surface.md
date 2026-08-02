# 0100 — Native duplicate-page surface, without removal

## Status

Accepted, and **partly superseded**. The Decision below states that nothing in Xoboro executes a
removal. That was false when it was written: the Komga-compatible surface already reached the
executor, and this document did not. See "Correction" at the end, which records what was actually
true and what changed as a result.

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

## Correction

The central claim above — "Nothing in Xoboro executes `DELETE_AUTO` or `DELETE_MANUAL`" — was not
true when this document was accepted. `RemoveDuplicatePagesTaskHandler` existed, was registered in
`XoboroRuntime`, called `SourceMutationAccess.removeArchiveEntries`, and incremented `deleteCount`.
It was reachable from `POST /api/v1/page-hashes/{hash}/delete-all` and `/delete-match` on the
Komga-compatible surface. So an operator's archives could already be rewritten, while this ADR, the
API doc, the console screen and the coverage row all said removal was unimplemented and
`deleteCount` was always `0`.

This is worth recording as a failure mode rather than as a typo. The decision "we will not build
this" and the fact "this is not built" are different claims, and only the first is an ADR's to
make. Writing them as one sentence made a design intention read as a description of the system,
and nothing checks a description that only exists in prose. Two hand-maintained documents agreeing
with each other said nothing about the code neither of them looked at.

What the decision above still gets right, and what is now implemented, is the shape: **deciding and
executing stay two separate steps.** `POST /api/xoboro/v1/duplicate-pages/{pageHash}/removals`
executes a recorded decision and refuses a hash that carries none, so no single call deletes
anything that was not already, separately, asked for. The reasoning for that split is exactly the
reasoning this ADR used to refuse removal outright.

Two properties were added to the executor at the same time, because it turned out to have neither:

- The rewrite is verified against the source's central directory before it replaces anything. It
  streamed with `ZipInputStream`, which reads sequential local headers, so a truncated archive
  could silently produce a rewrite missing entries nobody asked to remove — and be moved into place
  with a removal count that matched the request exactly.
- The original can be preserved. A quarantine directory, unset by default, is where the original
  goes instead of being overwritten. The irreversibility this ADR objected to was real and was
  already shipping.

Still deliberately not implemented: an **automatic policy** that executes `DELETE_AUTO` unattended
during a scan. That is the part of the original objection that survives intact — a sweep rewriting
an operator's archives with nobody watching is a different proposition from an administrator
executing a decision they recorded, and it is not implied by having built the latter.
