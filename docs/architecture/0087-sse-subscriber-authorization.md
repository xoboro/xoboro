# ADR 0087: SSE subscriber authorization

- Status: Accepted
- Date: 2026-07-29

## Context

The Komga-compatible event stream published catalog changes to every
authenticated subscriber. `KomgaSseEventHub.publish` accepted only an
`adminOnly` flag and a `userIdOnly` identifier, and `KomgaSseEventBridge`
passed neither for `LibraryEvent`, `CatalogMutationEvent`,
`OrganizationEvent`, or `ArtworkEvent`. A reader therefore received library,
series, book, collection, read list and thumbnail events for content they
could not fetch. The payloads carry identifiers, so the disclosure was of
identifiers plus the timing and volume of change traffic rather than of
content — but every other read path pushes a `CatalogAccess` check into SQL
specifically to prevent that.

The stream also froze the subscriber's identity. The route read
`principal.user` once and stored that instance for the life of the
connection, and the connection had no maximum lifetime. `UserLifecycle`
deliberately expires sessions when authorization-relevant fields change, and
`deleteUser` expires them too, but nothing closed an open stream. Revocation
is evaluated per request, and an event stream is a single request that never
ends, so revoking a grant, stripping a role, or deleting an account left the
server streaming against the authorization snapshot taken at connect time.

ADR 0042 and ADR 0056 both enumerate the stream's filter dimensions as
administrator role and user identity, and neither mentions library access.
Nothing in the repository records the broadcast as intended, and ADR 0042
states that catalog, organization, artwork and session event producers still
need differential coverage.

Whether Komga itself filters its stream by library access is not established
by anything in this repository. `docs/compatibility/protocols.md` records a
source checksum for Komga's SSE controller but preserves nothing about its
behavior, Komga's source is not vendored, and the checksum-locked OpenAPI
snapshot contains no SSE path.

## Decision

- Scope events to subscribers who can access the event's library, wherever
  the domain event carries a `LibraryId`. This covers `LibraryEvent` and
  `CatalogMutationEvent`, which together are the entire scan firehose.
- Keep the check as in-memory grant membership rather than an entity lookup.
  The identifier travels inside the domain event, so scoping is exact for
  removals as well: a deleted row does not make its own removal event
  undeliverable, and clients do not retain phantom entries.
- Leave `OrganizationEvent` and `ArtworkEvent` broadcast, and record that as
  a known gap. Their payloads carry no library scope — a collection may
  legitimately span libraries, and an artwork event carries only owner kind
  and identifier — so scoping them would require a per-event lookup that
  cannot resolve a deleted owner.
- Re-resolve the subscriber's user row on every stream wake and close the
  stream when it is gone or when any field that `UserLifecycle.updateUser`
  compares before expiring sessions has changed. Bound the lookup to at most
  one per recheck period per subscriber.
- Require the user-snapshot collaborator as a constructor argument with no
  default, so a call site cannot silently omit the stream's only revocation
  control.

## Consequences

A restricted reader no longer learns identifiers or change timing for
libraries they cannot access, and an open stream now closes within the
recheck period of a revocation instead of outliving it indefinitely.

Because scoping is applied at publication against grants rather than by
re-reading entities, it does not reproduce `ContentRestrictions`. Age
restrictions and sharing labels are per item and need metadata the event does
not carry, so a subscriber with a library grant still learns that something
changed in that library even when the item itself would be filtered from
their catalog reads. This is a deliberate, documented reduction of the leak
rather than its closure.

The recheck is a behavioral difference from the previous stream and from
Komga: an event stream that stays open is now subject to periodic
re-authorization, so a client holding a connection across an administrative
change is disconnected and must reconnect. Clients already have to tolerate
this. ADR 0056 defines SSE delivery as an invalidation channel rather than a
durable log and requires reconnect recovery to refresh current state, and the
hub already drops events for slow consumers under
`BufferOverflow.DROP_OLDEST`.

ADR 0082 supersedes exact wire compatibility with feature coverage and
directs reimplementing unsafe behavior with secure internals while recording
deliberate differences. This decision follows that directive, so it does not
depend on resolving what Komga does.

The remaining organization and artwork broadcast is asserted by a test, so
narrowing it later is a deliberate change rather than incidental drift. The
native event stream defines its own per-subscriber filtering separately and
does not inherit this compatibility gap.
