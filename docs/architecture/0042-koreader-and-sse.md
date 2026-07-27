# ADR 0042: KOReader synchronization and authenticated server events

- Status: accepted
- Date: 2026-07-27

## Context

KOReader identifies documents with a sparse partial-MD5 fingerprint and
represents EPUB progress as a manifest resource index. Komga's browser clients
also depend on a long-lived authenticated SSE stream for progress, library, and
task updates. Both facilities must remain usable when Xoboro adds Novel, Video,
and Audio storage rather than becoming coupled to the Komga Book table.

## Decision

- Index source-content fingerprints by canonical `MediaItemId` and a named
  fingerprint algorithm. The current SQLite adapter maps KOReader partial-MD5
  to the legacy Book storage row; future media types can share the boundary.
- Compute KOReader's exact sparse MD5 while an item is already materialized for
  analysis. Preserve its offset and full-buffer behavior for client
  compatibility, close files deterministically, and skip work unless the
  Library setting requests it.
- Authenticate KOReader with an API key in `X-Auth-User` and require the
  `KOREADER_SYNC` role. Preserve duplicate-hash conflict behavior and translate
  both KOReader EPUB locator forms to durable Readium progression.
- Use a bounded per-subscriber event channel. Slow clients discard their oldest
  pending notification instead of retaining unbounded server memory.
- Authenticate SSE through the normal Komga providers, filter user events at
  publication time, filter administrator events by role, send 15-second
  heartbeat comments, and refresh task counts every 10 seconds.
- Publish progress and Library lifecycle events from application boundaries;
  do not make route handlers poll storage for those changes.

## Consequences

KOReader hashing shares source materialization with normal analysis and avoids
an extra scan pass. Progress survives restart and remains consumable through
both KOReader and WebPub. SSE subscribers are isolated from each other and from
the task worker, while shutdown closes all subscriptions. More catalog,
organization, artwork, and session event producers still need differential
coverage before the SSE surface is certified compatible.
