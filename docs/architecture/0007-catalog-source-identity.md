# ADR 0007: Separate catalog location from source identity

- Status: accepted
- Date: 2026-07-27

## Context

Komga identifies scanned books primarily by URL. Renaming or moving a file therefore
looks like a deletion plus an addition unless later content analysis can recover it.
That loses a useful opportunity to preserve metadata and progress without opening the
media file.

## Decision

- Persist both the source item ID and its normalized library-relative path.
- Persist an optional opaque source identity for books.
- Local inventory obtains this identity from the filesystem file key when available.
- Never interpret the identity outside its source adapter.
- Keep hashes independent: an identity is a move hint, not content integrity evidence.
- Retain Komga-compatible file timestamps, file and KOReader hashes, trash dates,
  one-shot flags, book numbers, and series book counts in the core entities.

## Consequences

A reconciler can detect many same-filesystem moves using metadata already returned by
directory traversal. Sources without stable identities remain supported and fall back to
location and later hash evidence. Hard links and reused filesystem keys require
ambiguity handling; identity alone must never merge multiple candidates.
