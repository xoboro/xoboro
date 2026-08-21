# Fast reader-ready analysis

## Goal

A newly scanned local CBZ must become usable by the reader before optional enrichment finishes.
The user-visible completion point is a persisted `READY` media row with a non-empty page manifest,
not a file hash, page dimensions, imported metadata, or generated artwork.

The fast path targets at least 10 reader-ready CBZ items per second on the development Mac with a
synthetic production-shaped workload. The production deployment currently has 299,280 CBZ items,
9,920,375 indexed pages, two task workers, and settings that request whole-file hashes and page
dimensions. Those optional settings must still be honoured eventually without holding reader
availability behind them.

## Current problem

`ANALYZE_BOOK` performs all work synchronously in one durable task:

1. read the whole archive for its file hash;
2. open every ZIP entry for media detection and image dimensions;
3. replace every page row one statement at a time;
4. re-open the first page to generate artwork;
5. refresh book and series metadata.

The latest production run completed 154,186 items in 15 hours 35 minutes, about 2.75 items per
second. Most of that work is not needed to list or serve pages.

## Design

### Reader-ready stage

`ANALYZE_BOOK` first invokes a focused reader-ready indexer. For a CBZ available through
`SourceRandomAccess`, it reads the ZIP central directory and persists the page manifest without
opening page bodies. Missing dimensions and hashes are represented by the existing nullable/blank
fields; `READY` already permits both.

If media is already `READY`, the reader-ready stage leaves it unchanged. If the source is not a
comic archive, lacks random access, or cannot be parsed as ZIP, the stage uses the existing full
analyzer so PDF, EPUB, RAR-disguised-CBZ, corrupt, and encrypted-media diagnosis remains correct.

After successful reader preparation, the handler enqueues one deterministic `ENRICH_BOOK` task at
low priority. The existing `ANALYZE_BOOK_<bookId>` identity and priorities remain the entry point
for scans, administrator requests, and the page-manifest priority bump.

### Enrichment stage

`ENRICH_BOOK` runs the existing full `AnalyzeBook.execute` path. It honours library settings for
whole-file hashes, page hashes, KoReader hashes, image dimensions, format detection, and complete
media diagnosis. On success it performs the existing book metadata refresh, book cover generation,
series metadata refresh, and series cover generation.

Its task ID is deterministic per book and its default priority is low. A library-wide scan can
therefore queue reader-ready work at high priority while enrichment accumulates behind it. Durable
task retry, lease, and dead-letter behavior is reused unchanged.

### Persistence

Page and media-file rows are inserted with JDBC batches inside the existing per-book transaction.
This preserves atomic replacement and row ordering while removing one database round trip per page.
No schema migration is required.

## Ordering and failure behavior

- A reader-ready task must persist a non-empty manifest before it reports success.
- Enrichment is never executed in the HTTP request handler.
- Opening an unready item continues to raise its `ANALYZE_BOOK` task to highest priority.
- Enrichment failure leaves the already persisted reader-ready manifest usable and is visible as a
  retrying or dead durable task.
- Reader-ready fallback failures retain the existing media `ERROR` or `UNSUPPORTED` outcomes.
- Re-analysis of an already-ready item does not temporarily erase dimensions, hashes, or pages.

## Verification

Automated tests must prove:

1. a CBZ requesting hashes and dimensions becomes `READY` without reading page bodies;
2. reader-ready completion queues exactly one low-priority enrichment task;
3. fast tasks are claimed before accumulated enrichment tasks;
4. enrichment restores full dimensions/hash/artwork behavior;
5. non-ZIP media uses the existing full analyzer;
6. batched persistence preserves page and file ordering and replacement semantics;
7. a synthetic production-shaped measurement reaches at least 10 reader-ready items per second.

The default backend gate remains `./gradlew check`. The performance measurement is explicit and
reports item count, page count, elapsed time, and throughput so the target cannot be claimed from a
unit test that does not exercise ZIP parsing and SQLite persistence.

## Non-goals

- Reducing the total bytes required by explicitly enabled enrichment settings.
- Claiming that all enrichment completes at 10 items per second.
- Changing reader API response shapes or media status values.
- Adding a new dependency or a schema migration.
