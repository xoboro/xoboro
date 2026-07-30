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

## Open: the intermittent tens of milliseconds

Unexplained, and deliberately recorded as unexplained.

`api.series_listing` is bimodal at 15,050 items: `min` sits at 3.5–4.1 ms in
every run — close to the 4.8 ms p50 measured at 3,050 items — while `p50` lands
between 25 and 75 ms and `max` reaches 300–1,000 ms. So the fast path is intact
and something intermittent adds tens of milliseconds to most requests.

The leading candidate is the harness itself: it does not wait for the task queue
to drain before measuring API latency, so at 15,050 items background analysis is
plausibly still running and competing for the write lock during the measurement.
At 3,050 items the queue drains fast enough that page-0 latency looked clean.
That is a methodology defect, not a known server defect, and it must be fixed
before any latency-versus-catalog-size claim can be made.

⚠️ An earlier reading of this data claimed latency grew 12–15× with catalog size.
That claim was wrong: it compared p50 values across runs, and the run-to-run
spread is threefold on its own.

## Also open: superlinear cold scan

`cold_full_scan` went from roughly 13 s at 3,050 items to 167.6 s at 15,050 — a
5× increase in items for a 12.8× increase in time. This is a single observation
per size and cold metrics need separate-JVM repetition before the shape can be
called superlinear with confidence, but it is the largest unexplained number the
harness produces and it deserves its own investigation.
