# ADR 0030: Durable per-user read progress

## Context

Reader resume, unread counts, and on-deck selection require progress to survive
server and client restarts. Progress is user-owned state and must follow the
same library and content restrictions as catalog reads. Recomputing every
series count from all books on every list request would become expensive for
large libraries.

## Decision

- Persist one progress row per MediaItem-compatible Book and user, including
  page, completion, read timestamp, device identity, optional locator payload,
  and audit timestamps.
- Maintain a durable per-series/user aggregate in the same transaction as
  progress mutations.
- Validate page progress against analyzed media page counts. Explicit
  completion resolves to the final analyzed page.
- Preserve progress creation time across updates and replace the read timestamp
  on every accepted mutation.
- Mark a series read with a batch mutation over active, analyzed books and mark
  it unread with one set-based delete.
- Project progress into Komga Book/Series DTOs and select on-deck books in SQL
  using the aggregate before pagination.
- Require an already authorized catalog item before every mutation.

## Consequences

Clients can restore a user's last page after reconnecting, series counts are
cheap to read, and on-deck does not scan the full in-memory catalog. EPUB R2
locator semantics, sync points, device mutation, and SSE notifications remain
separate compatibility work.
