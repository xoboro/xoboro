# ADR 0008: Batched set-based catalog reconciliation

- Status: accepted
- Date: 2026-07-27

## Context

A library inventory can contain hundreds of gigabytes and many thousands of books.
Holding every entry in memory or issuing repository queries per file would make scan
memory and database round trips grow with the complete library. A failed source entry
must also never make a partially observed library look deleted.

SQLite values use dynamic typing. Plain jOOQ SQL metadata can expose declared `INTEGER`
columns as 32-bit values even when SQLite stored a 64-bit epoch or file size.

## Decision

- Stream source metadata into bounded batches of 500 candidates by default.
- Persist batches in a scan-session staging table and reconcile them with set-based SQL.
- Match the current location first, then use only unique opaque source identities as
  move hints. Ambiguous identities never merge books.
- Detect new, changed, moved, restored, and missing books without opening media files.
- Create and aggregate series from candidate paths, including root and one-shot series.
- Soft-delete unseen books and series only after a complete inventory with zero failed
  entries. Partial inventories preserve unseen rows and never reduce series counts.
- Clear stale hashes on changed media and enqueue analysis tasks in the same transaction
  as catalog changes. Deep scans enqueue unchanged books as well.
- Remove staged candidates after completion or abort while retaining the session result.
- Read 64-bit timestamps and file sizes through explicit text casts before converting to
  `Long`; read aggregate counts through `Number`.

## Consequences

Inventory memory is bounded by traversal state and batch size. Reconciliation database
work scales by SQL sets instead of per-file repository round trips. Catalog updates and
analysis scheduling are atomic, repeated identical scans preserve IDs, and interrupted
processes cannot expose a half-reconciled catalog.

Remote inventories can use the same contract. Archive analysis, metadata import, and
thumbnail generation remain independent durable tasks.
