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

## Also open: superlinear cold scan

`cold_full_scan` measured 11.4 s at 3,050 items and 149.5 s at 15,050 — a 5×
increase in items for a 13.1× increase in time. An earlier run measured 167.6 s at
the same size, so the shape is consistent across two observations but the magnitude
varies by 12%.

Cold metrics need repetition in **separate JVMs**, which
`scripts/cold-scan-repetitions.sh` now does: one `gradlew` invocation per
repetition, because looping inside one JVM makes every iteration after the first
systematically faster and stops measuring a cold start at all. At 105 items five
repetitions vary by about 4%.

Running it at 3,050 and 15,050 items is the outstanding work. The two-point ratio
above is suggestive but two points cannot distinguish superlinear growth from a
fixed cost that happens to land between them.

## Settled: a WebDAV library is bounded by the link, not by the adapter

Same library from both sides — 234 series, 18,211 CBZ archives, 129.1 GB, average 7.26 MB
per archive:

| | listing | analysis | total |
|---|---|---|---|
| `local`, on the machine holding the files | 95 s | 5.5 items/s | **56 min** |
| `webdav`, from another machine over a ~6 MB/s link | 87 s | 0.60 items/s | **~6 h** |

Listing is not the difference: 234 `PROPFIND`s cost about the same as walking a directory
tree. Analysis is, and the reason is a design choice rather than a protocol limit.
`SourceMediaAccess.materialize()` returns a `Path`, so a remote source must produce a whole
local file before an analyzer can open it. 129 GB over ~6 MB/s is about 6 hours, and the
measured remaining time tracked that floor.

**The adapter is not leaving throughput on the table.** It sustains 4.36 MB/s against a link
that measured 4.4 MB/s on one stream and 6.3 MB/s on four. Four task workers each fetch then
analyze in sequence, so a connection idles while its archive is being read; 69% of the
four-stream ceiling is what that structure predicts.

Three earlier readings of this said otherwise and were all measurement errors worth
recording, because each is easy to repeat:

- **0.30 items/s** — measured while a local-source benchmark was analyzing *the same external
  disk on the other machine* at 5.5 items/s. The disk was the shared bottleneck. Comparing
  two configurations by running them at the same time against one disk measures neither.
- **144 items/h over 24 h** — dragged down by the transport being absent, not by slow work.
  The SSH tunnel carrying the connection failed to connect 169 times in that day
  (`ssh: connect to host ...: Undefined error: 0`), and every WebDAV `GET` during those gaps
  got `Connection refused`.
- **"we are at 36% of the link ceiling, so the problem is ours"** — arithmetic on the first
  number above. With the confound removed it is 69%, which is what the fetch-then-analyze
  structure predicts, so there was nothing to find there.

What would actually lower the floor is not fetching whole files. The server advertises
`Accept-Ranges: bytes`, and on one 20.5 MB archive a trailing 2 KB range returned in 0.24 s
against 15.2 s for the whole file — a ZIP's central directory is at the end, and an analyzer
needs kilobytes of it. That is an SPI change (`materialize` cannot express it) and is not
free: with `analyzeDimensions` on, per-page headers mean many round trips at 23 ms RTT, so
it only wins if reads are batched into windows covering many entries rather than issued per
page. `hashFiles`, on by default, genuinely needs the whole file and would have to be off
for a remote library to benefit.

