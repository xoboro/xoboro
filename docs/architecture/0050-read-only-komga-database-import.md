# ADR 0050: Read-only Komga database import

## Status

Accepted.

## Context

Replacing an existing Komga installation requires more than rescanning media.
User identities and restrictions, metadata locks, read progress, organizations,
artwork, and operational history live in Komga's SQLite database. A migration
must preserve those relationships without mutating the source or leaving a
partially imported Xoboro database.

## Decision

- Accept only the checksum-known Komga 1.25 schema version. Validate required
  tables and columns and run SQLite `quick_check` before reading any state.
- Open Komga through a read-only URI, enable `query_only`, and hold one
  transaction for a consistent source snapshot.
- Require an empty Xoboro target by default. `--replace` explicitly clears all
  imported roots inside the same destination transaction.
- Preserve stable identifiers, password hashes, restrictions, metadata and
  locks, media indexes, progress and bounded locators, collections, read lists,
  embedded artwork, page-hash policies, history, API keys, authentication
  activity, and client settings.
- Map archive, EPUB, and PDF books onto the canonical Comic, Novel, and Book
  `MediaItem` subtypes. Komga Book and Series remain compatibility adapters.
- Rebuild Xoboro's full-text catalog index before committing.
- Never copy deployment-specific server settings or Komga synchronization
  snapshots. External artwork sidecars are rediscovered through normal source
  analysis after cutover.
- Expose inspection, import, and explicit replacement as offline commands.

## Consequences

Any validation or row failure rolls back the entire Xoboro import and leaves
the Komga database unchanged. Operators can compare an imported snapshot before
cutover and retain their verified Komga backup for rollback. Supporting another
Komga schema requires an explicit importer update and a matching synthetic
fixture rather than best-effort column guessing.
