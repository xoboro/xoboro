# 0105 — `totalProgression` is one position ahead of Readium, and stays that way for now

## Status

Superseded by ADR 0106, which carried out the correction this ADR deferred.

The analysis below is retained because it is still accurate about the defect and about
what depended on it — ADR 0106 builds on it rather than replacing it. Two things in it are
now out of date: the Decision to leave the arithmetic alone, and the correction sketched in
"Precondition" as `(position - 1 + progression) / count`, which ADR 0106 rejects in favour
of `(position - 1) / count`. `progression` is measured against the spine item rather than
the publication, so folding it in pushes a position past its own slot.

## Context

`/api/xoboro/v1/media-items/{id}/positions` exposes EPUB reading order, and the
first-party reader added in the web UI copies `progression` and `totalProgression`
straight into the locator it writes back. Reviewing that path surfaced the value's
actual arithmetic, in `EpubMediaAnalyzer.computePositions`:

```kotlin
totalProgression = draft.position.toFloat() / raw.size
```

`position` is one-based, so for a two-position publication the positions report
`0.5` and `1.0`. A Readium locator's `totalProgression` is `0` at the start of a
publication. This value is therefore the progress at the **end** of a position,
one position ahead of the convention the field name implies.

It is also internally inconsistent with its sibling. The first position of a
freshly opened book carries `progression = 0` — the start of that resource — beside
`totalProgression = 0.5`. Read as a locator, that pair says "at the beginning of
chapter one, and halfway through the book".

The documentation made this worse rather than flagging it: `native-v1.md` and the
DTO both described the pair as "what a Readium locator carries", which is the one
thing it is not. The route's own test fixture used `(position - 1) / count` — `0`
then `0.5` — so the test described an API that does not exist, which is why nobody
noticed.

## What actually depends on it

An earlier revision of this ADR claimed the value was purely presentational and
"does not lose anyone's place". **That was wrong**, and an adversarial review caught
it. The corrected account:

- **The first-party EPUB reader is unaffected.** It resumes through
  `resumePage(readProgress, order.length)`, which works from the stored page. It
  copies `totalProgression` into the locator it writes but never reads it back to
  decide where to open.
- **Kobo's reported percentage is affected.** `KoboRoutes` sets
  `ProgressPercent = totalProgression * 100`, so a device opening chapter one of a
  two-chapter KEPUB is told 50%.
- **KOReader turns it back into a stored page.** This is the part that was missed.
  `KoreaderSyncRoutes.pageFor` is `round(pageCount * position.totalProgression)`,
  its result becomes `mapped.page`, and that is persisted through
  `updateBookProgression`. So the value reaches durable read progress rather than
  stopping at a display.

The coupling runs the opposite way to what a first reading suggests, which is why
this needs stating precisely. `pageFor` multiplies by `pageCount` immediately after
`totalProgression` divided by the position count, so the two partly cancel: with the
**current** convention the last position maps to the last page. Under the Readium
convention `(position - 1) / n`, `pageFor` computes `round(pageCount * (n - 1) / n)`,
which no longer lands on `pageCount` by construction.

The cancellation is only partial, because `pageCount` and `positions.size` are not
the same number: positions are chunked by `knownSize` (uncompressed) while
`pageCount` sums `ceil(compressedSize / POSITION_BYTES)`, so `pageCount ≤ n` for any
compressed EPUB. The residual error in a stored KOReader page is bounded by about
one page, and rounds to zero when `pageCount` is much smaller than `n`. That is also
why the Readium convention does not simply cost a reader the final page: when
`pageCount` is much smaller than `n`, `round(pageCount * (n - 1) / n)` rounds back up
to `pageCount` and the last page is still reachable. What is guaranteed is that the
mapping stops being exact, not any particular unreachable page.

So: the defect is small and bounded, but it is **durable, not presentational**, and
correcting `totalProgression` alone would introduce a worse bug than it fixes.

## Decision

**Leave the arithmetic alone on this branch. Do not paper over it in clients.**

The deciding factor is **not** migration cost. An earlier revision said it was, and
that argument does not survive scrutiny either: `totalProgression` is a pure function
of `position` and `positions.size`, both stored and both correct, so it could be
recomputed at the read boundary in `JooqBookMediaRepository` with no migration and no
re-analysis. The stored column would simply stop being the source of truth.

The real reason is that the arithmetic is not independent. `KoreaderSyncRoutes.pageFor`
inverts it, so changing one without the other moves every KOReader user's stored page
by up to about a page, by an amount that depends on the book's own `pageCount`-to-`n`
ratio. A correction is therefore a coordinated change across the analyzer, the KOReader
mapping and Kobo's percentage — three
surfaces, two of them device protocols with no round-trip acceptance coverage — for a
bounded sub-one-page error. That is a piece of work with its own acceptance criteria,
not a detail of adding a web reader, and doing it half-way is worse than leaving it.

Two things follow, and both are deliberate:

- **The reader keeps echoing the server.** It would be easy to have it compute a
  coherent `(position - 1 + progression) / count` locally. That is rejected: the
  reader's locators would then disagree with the positions listing, and the
  percentage a Kobo device saw would depend on which client wrote last. One wrong
  convention consistently applied is recoverable. Two conventions in one progress
  table is not.
- **The documentation states the arithmetic, not the intent.** `native-v1.md` and
  the DTO now say `position / count` and say it is one position ahead of Readium.
  The route's test fixture asserts `0.5` and `1.0`, which is what makes the
  deviation visible at the place someone would change it.

## Precondition for correcting it

The correction is `(position - 1 + progression) / count`, and it needs, in one
change:

1. the analyzer formula — or, cheaper and with no migration, a recomputation at the
   read boundary in `JooqBookMediaRepository`, since the inputs are already stored;
2. `KoreaderSyncRoutes.pageFor`, which inverts the current convention and would
   otherwise turn corrected values into stored pages that are off by up to about one.
   This is the item that makes the change coordinated rather than local, and it was
   missed in the first version of this ADR;
3. `EpubMediaAnalyzerTest.kt:53`, which asserts the last position's
   `totalProgression` is `1F` and so pins the current convention — under the
   correction the last of two positions is `0.5` — plus the delivery-route fixture;
4. a decision about Kobo and KOReader devices that have already synced under the old
   convention and will see their position move.

Item 2 is the technical blocker and item 4 is the product one. Neither is expensive
on its own; what makes this a task of its own is that a round-trip through both device
protocols has no acceptance coverage today, so there would be nothing to tell you the
coordinated change was right.

## Consequences

- A reader or client reading these values should treat `totalProgression` as "how
  far through the publication this position ends", which is what it is.
- A KOReader user's stored page can sit up to about one page ahead of where they
  were. A Kobo user's reported percentage is wrong by one position. Neither loses a
  chapter, and the first-party reader is unaffected.
- The next person to look at this finds the arithmetic, the coupling to `pageFor`,
  and the list above, instead of rediscovering it from a test fixture that disagreed
  with production.
- Two claims in the first version of this ADR were wrong and are corrected above:
  that the value never reaches durable state, and that migration was the obstacle.
  Both were found by adversarial review, not by writing the document more carefully —
  which is the argument for having the review.
