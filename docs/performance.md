# Performance

How Xoboro's performance is measured, and the decisions that measurement has settled.

Run the harness with:

```
./gradlew performanceHarness \
  -Pxoboro.perf.seriesCount=1500 \
  -Pxoboro.perf.booksPerSeries=10 \
  -Pxoboro.perf.oneShotCount=50
```

It is excluded from `check` and never runs in CI. Output is emitted twice: stable
`xoboro.perf.<key>=<value>` lines for diffing, and a markdown table.

## Reading the numbers

Two rules, both learned by getting them wrong first.

**Do not compare across runs.** Three runs of the same code at the same size
(15,050 items) produced `api.series_listing.p50` of 74.6, 60.3 and 25.6 ms — a
threefold spread. Any conclusion drawn from a single run, or from comparing one
run against another, is reading noise.

**Compare within a run.** Two probes taken back-to-back in the same run share
whatever conditions that run had, so their ratio is meaningful even when their
absolute values are not. Every decision below rests on a within-run comparison.

**Drain the task queue before measuring latency.** Opening a runtime on a large
library enqueues a great deal of background work — 10,296 tasks at 15,050 items,
taking 267 s to clear — and a latency number taken while that runs is not a latency
number. The harness waits and reports `queue_drain.*`; a run that did not drain says
so and is not comparable with one that did.

**Repeat cold metrics across separate JVMs, not in-process.** `cold_scan`,
`cold_analyze` and `cold_full_scan` are by definition the first execution in a
JVM's life. Looping them inside one JVM makes later iterations systematically
faster as the JIT warms up — that is a trend contaminating the sample, not noise
that averages out, and a median quietly absorbs it. Call the Gradle task N times
from a shell loop instead. `unchanged_rescan` is the exception: a real deployment
re-runs it warm on every `scanInterval`, so in-process repetition measures the
right thing there.

**Report retry time separately from server time.** Any metric that retries must
report the successful attempt's own duration, the failure count and type, and the
total wall clock as separate numbers. A total dominated by backoff sleep measures
the harness, not the server — see `api.first_series_read_after_scan`, which once
reported 1,257 ms for a request whose real cost was 36.9 ms.

## Decision: no cursor pagination

**Measured and declined.** Cursor pagination exists to fix offset pagination's
cost growing with the offset, because the database still walks and discards the
skipped rows. That cost was measured and it is not there.

Within-run, same endpoint, first page versus the deepest page that exists:

| Catalog | Endpoint | First page | Deepest page | Page index |
|---|---|---|---|---|
| 3,050 items | `/series` | 4.8 ms | 3.6 ms | 17 |
| 3,050 items | `/media-items` | 7.8 ms | 7.1 ms | 152 |
| 15,050 items | `/media-items` | 31.7 ms | 32.3 ms | **752** |
| 15,050 items | `/series` | 25.6–60.3 ms | 66.3–66.8 ms | 77 |

⚠️ The two 15,050-item rows were measured **before** the queue drain existed, so
their absolute values are inflated (see below). The decision stands anyway, because
it rests on the first-page-versus-deep-page *ratio* within each run, and both pages
were measured under the same conditions. A drained re-measurement puts `/series` at
3.8 ms first page against 3.1 ms deepest — still no deep-page penalty, and the
conclusion is unchanged.

At 752 pages deep the penalty is 2%. At 3,050 items the deepest page is *faster*
than the first. Adding a second pagination mode to every collection endpoint —
permanently, to every client — is not justified by a 2% deep-page penalty.

Two further probes narrowed what the per-request cost is *not*:

- **Not the rows returned.** Asking for ten times as many rows (`size=20` →
  `size=200`) cost 24% more, not 10×. Row fetching and serialisation do not
  dominate.
- **Not the size of the filtered set.** Narrowing the counted set ~31× with
  `oneShot=true` did not make the request faster; in the same run it measured
  41.3 ms against 25.6 ms for the unfiltered listing. The leading hypothesis —
  that the `totalItems`/`totalPages` count query dominates — is therefore
  **rejected**, not merely unconfirmed.

## Settled: the intermittent tens of milliseconds were the harness

**Resolved, and the recorded hypothesis was right.**

The harness used to start measuring API latency the moment the runtime opened,
without waiting for the durable task queue. It now drains the queue first and
reports the outcome as `queue_drain.wall`, `queue_drain.drained` and
`queue_drain.remaining_tasks`.

The wait is not incidental:

| Catalog | Time to drain | Tasks left when it timed out |
|---|---|---|
| 105 items | 1.5 s | — |
| 3,050 items | 29.9 s | — |
| 15,050 items | 267 s | 10,296 at a flat 2-minute deadline |

At 15,050 items the queue held **over ten thousand tasks** and took four and a half
minutes to clear. Every `api.*` number previously measured at that size was taken
while that work was competing for the SQLite write lock.

With the queue drained, the bimodality is gone:

| Catalog | `api.series_listing` p50 | min | max |
|---|---|---|---|
| 3,050 items | 2.4 ms | 1.9 ms | 4.3 ms |
| 15,050 items | 3.8 ms | 2.9 ms | 5.1 ms |

Previously the same metric at 15,050 items read p50 25–75 ms with a max of
300–1,000 ms. Now every metric in the run has a max within 1.5× of its p50.

**So latency does not grow with catalog size the way the earlier numbers
suggested.** A 5× larger catalog costs 2.4 → 3.8 ms, and part of even that may be
run-to-run variation. The claim retracted earlier in this document — 12–15× growth
— was wrong twice over: it compared across runs, *and* both runs were measuring
background work.

The drain deadline is proportional to catalog size (30 ms per item, floor 30 s),
calibrated from the 3,050-item observation of ~10 ms per item. A flat deadline
merely moved the defect from "measured while busy" to "reports not drained on every
large run", which is the same blindness. A run that still fails to drain prints the
pending count **broken down by task type** and marks itself not comparable, rather
than quietly producing clean-looking numbers.

### What the noise was hiding

Fixing the measurement revealed a real difference the noise had masked. Within one
run at 15,050 items, all drained and all stable:

| Endpoint | p50 | max |
|---|---|---|
| `/series` | 3.8 ms | 5.1 ms |
| `/series` with a narrow filter | 1.6 ms | 3.2 ms |
| `/media-items` | 25.3 ms | 26.3 ms |

**`/media-items` is about 7× more expensive than `/series` at this size.** That was
invisible before, because the series listing's noise band (25–75 ms) sat directly on
top of the media-item listing's real cost. This is a within-run comparison and
therefore a legitimate one, and it is a server property rather than a harness artefact.

### Localised: the media-item listing sorts the whole catalogue to return ten rows

Timing the three steps of `findBooks` and `findSeries` separately, unfiltered, at 15,050 items:

| step | `/media-items` (page of 10) | `/series` (page of 200) |
|---|---|---|
| `count(*)` | **14.7 ms** | 0.59 ms |
| the page of ids | **13.5 ms** | 0.46 ms |
| hydrating them | 1.1 ms | 3.3 ms |

Hydration is not the cost, and the page size is not either — the smaller page is the
expensive one. What stands out is that fetching **ten ids costs as much as counting all
15,050 rows**, which a paged query has no business doing. `EXPLAIN QUERY PLAN` says why:

```
SCAN b
SEARCH bm USING COVERING INDEX sqlite_autoindex_book_metadata_1 (book_id=?)
SEARCH s USING COVERING INDEX sqlite_autoindex_series_1 (id=?)
SEARCH sm USING INDEX sqlite_autoindex_series_metadata_1 (series_id=?)
USE TEMP B-TREE FOR ORDER BY
```

The listing's default order is `sm.title_sort COLLATE NOCASE ASC, b.id ASC` — a column two
joins away, on the *series'* metadata. No index on `book` can satisfy it, so SQLite scans
every book, joins each to three tables, sorts all of them in a temporary b-tree, and
returns ten. `/series` runs the same shape against a tenth of the rows and pays a tenth.

Growth is the sort's, not the scan's: count-plus-ids goes 4.28 ms at 3,050 items to 28.2 ms
at 15,050 — 6.59× for 4.93× items, an exponent of 1.18, which is what `n log n` looks like
over this range. Projected at the deployed 145,105 books that is on the order of 400 ms per
listing request. **Treat that as a projection**; the last one in this file was 20× wrong.

**What a fix would take, and why it is not in this pass.** The sort key has to become
reachable from an index on `book` — i.e. denormalised onto the book row and maintained when
a series' title changes, which is the same cascade the search index already runs on rename.
That is a migration plus triggers. The alternative, changing what the listing is sorted by,
is a product decision about what users see and not a performance fix. Neither belongs in a
change whose subject is something else.

### The other half has a cheaper fix, measured

The count runs through the same `FROM` the page query needs, and none of those joins can change
what it counts. Every one is at most one row per book: `series` and `series_metadata` and
`book_metadata` are mandatory 1:1 (`initialize_book_metadata` and `initialize_series_metadata`
guarantee the metadata rows, and V14 backfilled the rest), `media.book_id` is a primary key, and
`read_progress` and `read_progress_series` are keyed on `(…, user_id)` against one user. So the
count is exactly the number of books matching the filter, whether or not the joins are there.

Counting the same 15,000 books both ways, ten repetitions:

| | per count |
|---|---|
| through the listing's joins | 8.90 ms |
| over `book` alone | **2.00 ms** |

Same answer both ways, 4.5× apart. It needs no schema change and changes no behaviour — what it
needs is for the filter builder to report which aliases it referenced, so the count can be given
only those joins. That is a refactor of the query builder rather than a one-line change, which is
why it is written down here rather than done.

Dropping a join the filter does reference would fail to compile the statement rather than return a
wrong number, which is the right failure mode for this.

⚠️ **A correction.** This document previously recorded that narrowing the filtered
set ~31× "did not make the request faster" (41.3 ms against 25.6 ms) and concluded
the count-query hypothesis was **rejected**. That comparison was made on undrained
numbers. Drained, the narrow filter is 1.6 ms against 3.8 ms — 2.4× *faster*. The
rejection does not stand.

**And the hypothesis is now answered directly, by timing the count rather than
inferring it from the request.** Unfiltered at 15,050 items, the media-item listing
spends 14.7 ms counting against 28.2 ms for count-plus-page: the count query is
roughly half the request, and it is proportional to the rows the filter matches,
which is why narrowing it helps. It was never possible to see that from the outside,
because the other half moves with the filter too.

## Settled: the cold scan was quadratic because every insert read the whole search index

This section used to end at "near-quadratic, cause unknown, look at `ADR 0052`". The cause was
not in ADR 0052 — the reconciliation SQL is fine — and it is now fixed. The measurement that
localised it is kept below, because it is the part that transfers.

At 15,050 items the first scan went from **102,738 ms to 1,969 ms**, and stopped curving.

| metric | 3,050 before | 3,050 after | 15,050 before | 15,050 after |
|---|---|---|---|---|
| `cold_scan.wall` | 4,404 ms | **468 ms** | 102,738 ms | **1,969 ms** |
| `cold_analyze.wall` | 7,953 ms | 6,211 ms | 34,575 ms | 33,144 ms |
| `unchanged_rescan.wall.p50` | 104.6 ms | 97.0 ms | 625.1 ms | 605.5 ms |

One run per cell on one machine, not the three-repetition averages the table further down
carries, so only the scan's 52× is outside the ±25% a single cold metric moves by. Analysis and
rescan are unchanged, which is what they should be: nothing touched them.

The scan is now **sub-linear** across these two sizes — 4.21× the time for 4.93× the items,
an exponent of 0.914 — because the per-item work is finally constant and what remains grows
with the directory walk rather than with the catalogue.

### Where it was

Timing each statement inside `JooqCatalogReconciliationStore.complete` put effectively all of it
in one place:

| step | 3,050 items | 15,050 items | ratio |
|---|---|---|---|
| `insertNewBooks` | 3,937 ms | **100,160 ms** | **25.4×** |
| every other step, summed | ~350 ms | ~2,000 ms | ~5.7× |

`ln(25.4) / ln(4.93) = 2.03`. That one statement was 92% of `cold_scan` at 3,050 items and
**97.5%** at 15,050.

### Why one `INSERT ... SELECT` was quadratic

`catalog_search_fts` and `catalog_title_substring` declare `entity_type` and `entity_id`
UNINDEXED. FTS5 offers no other way to carry a column it must not tokenise, so this is not an
oversight — but it does mean FTS5 cannot seek on either:

```
EXPLAIN QUERY PLAN DELETE FROM catalog_search_fts
  WHERE entity_type = 'BOOK' AND entity_id = 'x';
`--SCAN catalog_search_fts VIRTUAL TABLE INDEX 0:
```

Inserting one book ran four of those scans: the two `AFTER INSERT ON book` triggers, plus the
two on `book_metadata` that `initialize_book_metadata` inserts into. Each read everything
indexed so far, so n inserts cost n × n.

Isolated outside the JVM, against the real migrated schema:

| variant | 3,000 books | 15,000 books | ratio |
|---|---|---|---|
| as shipped | 3,024 ms | 74,438 ms | 24.6× (exponent **1.99**) |
| with the deletes removed | 100 ms | 377 ms | 3.77× |

The deletes could not remove anything: they searched for a primary key created a moment
earlier. The index built without them was identical row for row — same count, same digest, same
match hits.

### The two changes

**V35** drops the four provably-empty deletes. The book- and series-level insert triggers go
with them rather than merely losing their delete: SQLite does not define the order of two
`AFTER INSERT` triggers on one table, and the source views join the entity's metadata row, so
keeping them would duplicate a row under one order and write nothing under the other. The
metadata-level triggers have no such ambiguity — `initialize_book_metadata` and
`initialize_series_metadata` put exactly one metadata row behind every entity, and both metadata
repositories upsert with `ON CONFLICT DO UPDATE`, so an INSERT trigger fires once per entity.

**V36** gives updates and deletes a key to address, since those genuinely do have a row to
remove. `catalog_search_key` holds one rowid per entity and both indexes store that entity under
it, so a removal is a b-tree seek. Measured directly — 200 single-row deletes against one index:

| addressed by | 20,000 rows | 100,000 rows | ratio |
|---|---|---|---|
| the UNINDEXED entity columns | 603 ms | 2,709 ms | 4.49× |
| the key's rowid | 89 ms | 88 ms | **1.00×, flat** |

`EXPLAIN QUERY PLAN` reports `SCAN ... VIRTUAL TABLE` for *both* of those — the rowid form only
differs by a `:=` suffix marking the constraint it pushed down. The plan text would have got this
one wrong; the clock did not.

This is where V31 left off. V31 stopped `catalog_search_book_update` firing on a write that
changed nothing, which is what made a re-scan affordable, but it did not make the firing cheaper;
that trigger runs once per updated row, so a re-scan genuinely changing m books still paid m
passes over the index. Keyed, each is a seek.

### What V36 costs to apply

Measured on `.backup` snapshots of the deployed catalogue — 145,105 books and 3,339 series, 148,444
entities, a 4.55 GB database. V35 takes **12 ms**. V36 takes **27 s** of statement time (26.6 and
27.2 across two snapshots), one-off at startup, and it got there in two steps:

| | V36 |
|---|---|
| rebuilding both indexes under new keys | 52.2 s |
| adopting the word index's own rowids as the keys, rebuilding only the other | **27 s** |

Guarding against a duplicated index row — grouping the adoption by entity and dropping the copy
that claimed no key — costs nothing measurable: the adoption is 5.11 s grouped against 5.13 s
plain, and the delete does not reach 0.2 s. On the deployed catalogue it removes no rows, which is
the direct confirmation that no entity there is indexed twice.

An FTS5 row's rowid cannot be changed, but nothing requires the key to be a *new* number. Having
`catalog_search_key` adopt the rowids the word index already uses means the largest table in the
database is never rewritten. The interior-match index cannot be adopted alongside it — its rowids
are its own, and both cannot be the key — so it is the one that is rebuilt.

Where the remaining 27 s goes, from one of the two runs:

| statement | cost |
|---|---|
| rebuilding the books' interior-match rows | 17.7 s |
| scanning the word index to adopt its rowids | 5.1 s |
| emptying the interior-match index | 1.6 s |
| rebuilding the series' interior-match rows | 0.3 s |

**Stopping here is a judgement, not an oversight.** Removing the remaining rebuild means the key
carrying one rowid per index rather than one per entity, which needs `AUTOINCREMENT`, a sentinel row
to lift the counter above both indexes' existing maxima so a new entity's two numbers cannot collide
with an adopted one, and a trigger on the key table to fill the second column. That is permanent
schema complexity bought with a one-off 20 s at upgrade, against a file that says to prefer
simplicity once correctness is met.

Verified at full size, on the snapshot: both digests unchanged (74,243,080 and 14,928,546), every
index row on the rowid its key names, no entity without a key, `PRAGMA integrity_check` = `ok`.

⚠️ **This section briefly claimed 2.6 s, extrapolated from synthetic fixtures. It was 20× low.**
Synthetic titles are a few characters; the real index holds 74.2 MB of text against roughly 1 MB
at the same row count. Rebuild cost tracks *bytes of text*, not rows, and no row-count
extrapolation can see that. Migration cost is worth measuring on a snapshot rather than projecting.

### What is still a full pass

Renaming a series rewrites its books' rows as one `rowid IN (...)`. A trigger cannot loop, so
that stays one pass over the index — which is what it already was. The gain is on the
single-entity statements around it.

### The measurement that found it, kept

`scripts/cold-scan-repetitions.sh 3 300 10 50` and `... 3 1500 10 50`, three repetitions
each in separate JVMs, about 5% spread at both sizes — the numbers as they stood before the fix:

| metric | 3,050 items | 15,050 items | ratio |
|---|---|---|---|
| items | — | — | **4.93×** |
| `cold_scan.wall` | 4,290 ms | **97,933 ms** | **22.8×** |
| `cold_analyze.wall` | 6,204 ms | 31,417 ms | **5.06×** |
| `cold_full_scan.wall` | 10,495 ms | 129,350 ms | 12.3× |
| `unchanged_rescan.wall.p50` | 100 ms | 595 ms | 5.95× |

Per item, analysis costs 2.034 ms then 2.088 ms — **1.03×, flat**. Scan costs 1.406 ms then
6.508 ms — **4.63×**. Scan throughput falls from 710 items/s to 154 items/s, and the
composition inverts: scan is 41% of `cold_full_scan` at 3,050 items and **76%** at 15,050.

**Why this excludes the fixed-cost explanation.** Both metrics come from the same JVM, the
same fixture and the same setup in each repetition. A fixed cost that happened to land
between the two sizes would inflate *both* ratios. Analysis came out flat. A fixed cost
cannot produce that asymmetry, so the growth is in the scan itself.

`ln(22.8) / ln(4.93) = 1.96`. Two sizes cannot *prove* a curve, but an exponent that close to 2
alongside a flat sibling metric pointed at O(n²) — something evaluating over a whole set once per
item. That inference held; the set turned out to be the search index rather than the candidate
table, so `ADR 0052` was the wrong place to look and per-statement timing was the right next step
rather than more sizes.

The projection this section used to carry — about **2.3 hours** for the scan step at the deployed
145,105 items — was never run, and is now moot. At the measured post-fix rate it is on the order
of 20 seconds.

**Repeat scans were never affected.** `unchanged_rescan.wall.p50` grew 5.95× for 4.93× items,
near linear, and costs 0.6 s at 15,050 items. That is V31's `WHEN NEW.x IS NOT OLD.x` guard
on the search-index triggers doing its job: a write that changes nothing is free. The cost
is in the *first* scan.

Cold metrics need repetition in **separate JVMs**, which
`scripts/cold-scan-repetitions.sh` does: one `gradlew` invocation per repetition, because
looping inside one JVM makes every iteration after the first systematically faster and stops
measuring a cold start at all. At 105 items five repetitions vary by about 4%.

### An earlier conclusion in this file, withdrawn and then restored

The WebDAV comparison below reports listing at 95 s against analysis at 5.5 items/s and
concludes "listing is not the difference". This section used to add that the conclusion does
**not** hold for local sources as the catalogue grows, because the dominant term switches to
scan. That was true of the measurement in front of it and false as a statement about the system:
the switch was a defect, not a property of scale. With it fixed, scan is 5.6% of
`cold_full_scan` at 15,050 items (1,969 ms of 35,113 ms) and analysis dominates again at both
sizes.

Worth keeping as a caution rather than deleting: "the dominant term switches as n grows" and "one
statement is quadratic" produce the same two-point evidence, and only the second one can be
fixed. Splitting the metric distinguished them; another size would not have.

## Settled: a WebDAV scan was bounded by how much it chose to transfer

The heading here used to read "bounded by the link, not by the adapter". That was half right and
the wrong half was the part that mattered. The link does cap throughput. But the amount crossing
it was a design choice: analysis needed about 5 KB per archive and was moving 7.26 MB.

Same library from both sides — 234 series, 18,211 CBZ archives, 129.1 GB, average 7.26 MB per
archive:

| | listing | analysis | total |
|---|---|---|---|
| `local`, on the machine holding the files | 95 s | 5.5 items/s | **56 min** |
| `webdav`, from another machine, whole-file `materialize` | 87 s | 0.60 items/s | **~6 h** |

Listing is not the difference: 234 `PROPFIND`s cost about the same as walking a directory tree.
Analysis was, and `SourceMediaAccess.materialize()` returning a `Path` is why - a remote source
had to produce a whole local file before any analyzer could open it. 129 GB at the measured link
rate is about six hours, and the remaining time tracked that floor exactly.

### Where the time actually went

Layer by layer, same 9,449,145-byte archive, measured end to end:

| layer | throughput |
|---|---|
| inside the serving container - disk plus the VM's bind mount | 32 MB/s cold, 126-133 MB/s warm |
| HTTP on the serving machine, no tunnel | 757-849 MB/s |
| through the SSH tunnel, one stream | **5.0-8.5 MB/s** |
| through the SSH tunnel, four streams | 12.2 MB/s aggregate |
| raw SSH channel, default cipher | 12.4-15.5 MB/s |
| raw SSH channel, `aes128-gcm@openssh.com` | 17.6-20.4 MB/s |
| HTTP over the VPN with no SSH tunnel, one stream | 10.3-18.6 MB/s |
| HTTP over the VPN with no SSH tunnel, four streams | 18.2 MB/s aggregate |

Two things fall out of that table. The serving side is free - the disk, the container bind mount
and Apache `mod_dav` together cost nothing measurable. And **the SSH tunnel roughly halves the
link**, because it is a second layer of encryption inside a VPN that already encrypts and
authenticates the path. Eight parallel streams over the VPN gave 17.9 MB/s against four streams'
18.2, so ~18 MB/s is the link, and parallelism past four buys nothing.

### Per-entry ranged reads are slower than the whole file

The obvious way to read less is to fetch each entry's header. Measured on the same archive and
link: **150 sequential 4 KiB range requests took 6.52 s - 43.5 ms each, essentially all round
trip - against 520 ms to fetch the entire 9 MB archive.** Concurrency does not save it either;
eight at a time still lands near the whole-file time. Anything needing bytes out of every entry
is cheaper to materialize, and `SourceRandomAccess` says so in its own documentation.

### What did work: one range request per book

A ZIP's central directory is at the end of the file. One 64 KiB suffix range returned in **25 ms**
and held the complete directory for all 79 entries of that archive - names, sizes, and the
encryption flag. That is the entire page list, for 1/145th of the bytes and one round trip.

`materialize()` could not express it, since it hands an analyzer a local `Path` and
`java.util.zip.ZipFile` needs a real seekable file. So the trailer is parsed directly
(`ZipCentralDirectory` over `SourceRandomAccess`), and `AnalyzeBook` takes that path only when
nothing in the library's settings needs entry bytes:

- `analyzeDimensions` off - image dimensions are in the entry data.
- `hashPages` off - page hashes are the entry data.
- `hashFiles` / `hashKoreader` satisfied already or off - a whole-file hash needs every byte by
  definition. Note this is per book, not per library: once a book's hash is recorded, later
  analyses of it take the cheap path even with the setting on.

A trailer that does not parse as a ZIP falls back to materializing, which is what a `.cbz` that
is really a RAR relies on.

The cost of the cheap path is what it cannot see: no dimensions, and media types guessed from
file extension rather than sniffed with Tika. An operator who wants pages verified is already
paying to read them.

### The cover was the other half, and it was the whole remaining cost

With analysis fixed, a real scan still transferred the library, and the WebDAV server's own access
log said so in one line per book:

```
"GET /<series>/<book>.cbz HTTP/1.1" 206   65557
"GET /<series>/<book>.cbz HTTP/1.1" 200 4120549
```

Exactly paired, 100 and 100 in a 200-line sample. The `206` is the trailer read working. The `200`
is the whole archive, fetched immediately afterwards — because `AnalyzeBookTaskHandler` generates
the book's cover in the same task, and the only way to read page 1 was to materialize:

```
AnalyzeBookTaskHandler.handle
  -> BookCoverGenerationLifecycle.generateForBook
       -> BookContentService.openPage
            -> WebDavSourceMediaAccess.materialize      <- the entire archive
```

Four worker threads were parked in `HttpClient.send` under that stack for fifteen minutes straight
while the queue made no progress. A thread dump found it; no log line would have, because nothing
was failing.

`ZipRangedEntryReader` closes it: two range requests per page — the local header first, because only
it says where the entry's data begins, then the data — and raw inflate for a deflated entry. Roughly
50 ms against 520 ms to fetch the archive. `openPage` takes that path for ZIP comic archives when a
ranged source is registered, so covers, thumbnails and ordinary reading all stop pulling whole
files; RAR, EPUB and PDF still materialize, since those readers need a real file.

The general shape worth remembering: **one entry by range is a win, every entry by range is a
loss.** Same mechanism, opposite conclusion, and only the count differs.

### Two comparisons that are not the same comparison

Komga on the same machine reports `Scanned 234 series, 18211 books, and 907 sidecars in
700.887526ms`. That number invites a 100x conclusion and does not support one:

- Komga reads the library **locally**: its container bind-mounts the library directory read-only
  and its own log reports `root=file:/data/`. It has no WebDAV client. Both it and the WebDAV
  server bind-mount the *same* host directory — an APFS volume over USB — which is what makes the
  protocol the only variable between them.
- That 700 ms line is a six-hourly **incremental rescan of an already-analyzed library**: a
  filesystem walk that opens no archive. Its first pass had to open all 18,211 too.

So the honest pairing is Xoboro-local (56 min, dominated by hashing and analysis) against
Xoboro-over-WebDAV, and the gap between those two was transfer.

### Earlier readings of this that were wrong

Each was a measurement error, and each is easy to repeat:

- **0.30 items/s** - measured while a local-source benchmark was analyzing *the same external
  disk on the other machine*. Comparing two configurations by running them simultaneously
  against one disk measures neither.
- **144 items/h over 24 h** - the transport was absent, not slow. The tunnel failed to connect
  169 times that day and every `GET` in those gaps got `Connection refused`.
- **"we are at 36% of the link ceiling, so the problem is ours"** - arithmetic on the first
  number. Corrected it was 69%, which is what fetch-then-analyze predicts, so there was nothing
  to find there. The real problem was one level up: not how fast bytes moved, but how many.
- **"concurrent crawlers on the serving machine are confounding this"** - ten crawler workers
  really were writing to that disk throughout. It made no difference, because the serving side
  measures 757 MB/s and the bottleneck is entirely the link. A plausible confound is still worth
  measuring rather than asserting, in either direction.
