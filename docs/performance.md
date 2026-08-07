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
therefore a legitimate one; it is the next thing worth investigating, and it is a
server property rather than a harness artefact.

⚠️ **A correction.** This document previously recorded that narrowing the filtered
set ~31× "did not make the request faster" (41.3 ms against 25.6 ms) and concluded
the count-query hypothesis was **rejected**. That comparison was made on undrained
numbers. Drained, the narrow filter is 1.6 ms against 3.8 ms — 2.4× *faster*. The
rejection does not stand: the hypothesis is open again, on better evidence.

## Open, and now localised: the cold scan is near-quadratic

This section used to say the two-point ratio on `cold_full_scan` was suggestive but could
not distinguish superlinear growth from a fixed cost landing between the sizes. That was
the right caution, and it is now answered — not by a third size, but by splitting the
metric.

`scripts/cold-scan-repetitions.sh 3 300 10 50` and `... 3 1500 10 50`, three repetitions
each in separate JVMs, about 5% spread at both sizes:

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

`ln(22.8) / ln(4.93) = 1.96`. Two sizes still cannot *prove* a curve, but an exponent that
close to 2 alongside a flat sibling metric points at O(n²) — something evaluating over the
whole candidate set once per item. `ADR 0052: set-based scan reconciliation` is the place to
look; `EXPLAIN QUERY PLAN` on the reconciliation queries will show whether a candidate
lookup is an index seek or a table scan.

Projected at the deployed catalogue of 145,105 items — `145105 / 15050 = 9.64×` items, so
roughly 84× time at exponent 1.96 — the scan step alone would be about **2.3 hours**. That
is an extrapolation from two points; treat it as an order of magnitude.

**Repeat scans are not affected.** `unchanged_rescan.wall.p50` grew 5.95× for 4.93× items,
near linear, and costs 0.6 s at 15,050 items. That is V31's `WHEN NEW.x IS NOT OLD.x` guard
on the search-index triggers doing its job: a write that changes nothing is free. The cost
is in the *first* scan.

Cold metrics need repetition in **separate JVMs**, which
`scripts/cold-scan-repetitions.sh` does: one `gradlew` invocation per repetition, because
looping inside one JVM makes every iteration after the first systematically faster and stops
measuring a cold start at all. At 105 items five repetitions vary by about 4%.

### One earlier conclusion in this file does not generalise

The WebDAV comparison below reports listing at 95 s against analysis at 5.5 items/s and
concludes "listing is not the difference". That is true for *that* comparison, where
whole-file `materialize()` made analysis the bottleneck across a remote link. It does **not**
hold for local sources as the catalogue grows: the dominant term switches to scan.

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
