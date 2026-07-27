# ADR 0056: Post-commit domain events for Komga SSE

- Status: accepted
- Date: 2026-07-27

## Context

Komga clients use one authenticated SSE stream to invalidate Library, Series,
Book, organization, artwork, progress, session, import, and task state. Emitting
from HTTP routes would miss scheduled scans and background metadata work, while
emitting inside a database transaction could expose state that later rolls
back.

## Decision

- Publish typed application events from successful lifecycle and persistence
  boundaries, independent of the Komga transport.
- Capture scan reconciliation events in the transaction, but publish them only
  after the transaction commits.
- Map domain events to the exact Komga 1.25 event names and DTOs in one
  compatibility bridge.
- Apply administrator and user-identity filtering in the bounded event hub.
- Emit metadata changes from both manual editing and provider refreshes.
- Emit import success and failure to administrators without coupling the task
  module to the SSE module.
- Keep task status as a periodic administrator snapshot because it represents
  current queue state rather than a durable entity mutation.

## Consequences

Background jobs and REST mutations now produce the same client invalidations,
failed transactions do not leak phantom changes, and future native Xoboro
events can consume the typed publishers without inheriting Komga DTOs. SSE
delivery remains an invalidation channel rather than a durable log; reconnect
recovery must refresh current state.
