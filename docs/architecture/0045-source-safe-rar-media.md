# ADR 0045: Source-safe RAR media

## Status

Accepted.

## Context

Comic libraries commonly mix ZIP/CBZ with RAR/CBR and may use an extension
that does not match the outer archive signature. Native unrar executables make
container and host support differ across amd64 and arm64. Conversion must not
replace catalog identity, reading progress, or metadata merely because the
physical file changes.

## Decision

- Detect ZIP, RAR4, and RAR5 from bounded outer signatures.
- Decode RAR 3 through RAR 7 in-process with junrar and a bounded dictionary.
- Reuse the existing natural page order, content detection, dimension, and
  leading/trailing hash contracts for RAR pages.
- Stream indexed RAR pages while retaining the archive and materialized source
  until the response closes.
- Convert RAR to CBZ as an entry stream with entry-count, expanded-size,
  duplicate-name, and traversal guards.
- Write replacements to a sibling temporary file and atomically move them into
  place. Update the existing media item with the new source facts, clear
  content hashes only when bytes changed, and schedule analysis plus a scan.
- Run extension repair and conversion as durable, per-media-item jobs after
  scans. Correctly named CBZ files are idempotent no-ops.

## Consequences

RAR support behaves the same on supported JVM platforms and does not require a
host executable. Xoboro only reads and converts RAR into ZIP; it never creates
RAR archives. Remote source adapters must implement the same mutation boundary
before they can opt into repair or conversion.
