# 0106 — `totalProgression` is the Readium convention, and `pageFor` no longer inverts it

## Status

Accepted. Supersedes ADR 0105, which deferred this correction and named its precondition.

## Context

ADR 0105 recorded, correctly, that `EpubMediaAnalyzer.computePositions` wrote

```kotlin
totalProgression = draft.position.toFloat() / raw.size
```

so a two-position publication reported `0.5` and `1.0` where a Readium locator reports
`0` and `0.5`. The value was the progress at the **end** of a position, one position
ahead of the field's name, and internally inconsistent with its sibling: the first
position of a freshly opened book carried `progression = 0` beside
`totalProgression = 0.5`.

ADR 0105 also established the part that made this more than cosmetic, and it is worth
restating because it is the whole reason this needed to be one change rather than three:
`KoreaderSyncRoutes.pageFor` was `round(pageCount * position.totalProgression)`, its
result became `mapped.page`, and that was persisted through `updateBookProgression`. The
two arithmetics partly cancelled, so under the *wrong* convention the last position
happened to land on the last page. Correcting the analyzer alone would have moved every
KOReader user's stored page by an amount that depended on the book's own
`pageCount`-to-`n` ratio.

## Decision

Correct it, in one change, and **decouple the page mapping from the locator field** so
that the coupling ADR 0105 describes cannot come back.

**The analyzer writes `(position - 1) / count`.** `EpubMediaAnalyzer.computePositions`.
The first position of any publication is `0`; the last of `n` is `(n - 1) / n`. No
position reports `1`, because `1` is the end of the publication rather than a place a
reader can be. The last position is recognised by `position == count`.

ADR 0105's sketch of the correction was `(position - 1 + progression) / count`, and that
is **rejected**. `progression` is the fraction through the *spine item*, not through the
position: a spine item chunked into four positions carries `0`, `0.25`, `0.5`, `0.75`
regardless of how many positions the rest of the publication has. Folding it into a
whole-publication ratio pushes a position past its own slot — in a four-position book that
formula yields `0`, `0.3125`, `0.625`, `0.9375` instead of the evenly spaced `0`, `0.25`,
`0.5`, `0.75` — and it destroys the property that consecutive positions are exactly
`1 / n` apart, which is what makes the mapping to pages invertible at all. Read
`progression` as the offset *within* a position and the two formulas coincide, because a
position's start has offset zero; `(position - 1) / count` is the form that does not
depend on which reading you take.

**`pageFor` reads the position index, not the locator field.**

```kotlin
private fun BookMedia.pageFor(position: MediaPosition): Int {
  val pages = pageCount.coerceAtLeast(1)
  val slots = positions.size.coerceAtLeast(1)
  return (((position.position - 1).toLong() * pages / slots).toInt() + 1).coerceIn(1, pages)
}
```

Positions divide the publication into `n` equal slots, so position `k` begins inside page
slot `floor((k - 1) * pageCount / n)`, one-based. Position 1 maps to page 1 by
construction. The last position maps to `pageCount` whenever `pageCount <= n`, which holds
for every reflowable EPUB: page count sums `ceil(compressedSize / POSITION_BYTES)` while
positions chunk on uncompressed size.

The reason not to simply invert the corrected `Float` — `floor(pageCount * totalProgression) + 1`
— is that `(n - 1) / n` is not representable in `Float` for most `n`, so the product lands
just above or just below the integer depending on `n`, and `floor` of the low case costs a
reader the final page. That is the exact failure ADR 0105 warned about, reintroduced by
rounding instead of by convention. Integer arithmetic on the stored index cannot do it, and
it makes the locator convention and durable read progress independent: a future change to
how progress is *displayed* can no longer move anybody's stored page.

**The KOReader percentage fallback matches.** `ReadProgress.totalProgression(media)` answers
the `percentage` field from the stored locator when there is one, and otherwise from the
page — for progress written by the Komga REST `read-progress` patch, which records no
locator. That fallback is now `(page - 1) / pageCount`, the start of the page, because it
answers the same field as the locator branch beside it. ADR 0105's own argument applies
here: one wrong convention consistently applied is recoverable, two conventions in one
field are not.

**Kobo's `ProgressPercent` stays a pass-through.** `KoboRoutes` still reports
`locations.totalProgression * 100` and is now correct by construction, which is also what
Komga reports. The consequence is deliberate and is stated below rather than papered over.

**Persisted values are migrated, not recomputed at the read boundary.** ADR 0105 floated
recomputing in `JooqBookMediaRepository` to avoid a migration. Rejected: it would leave
`media_position.total_progression` holding a value nothing reads, which is an SSOT
violation dressed as a saving. `V30__readium_total_progression.sql` restates the column
instead. It is data only — no column added, dropped, renamed or retyped — so the schema is
unchanged and a rollback to the previous application version reads the shape it wrote. The
values are a pure function of `position` and the per-book row count, neither of which the
statement touches, so the previous convention is recoverable exactly by the inverse
`UPDATE` quoted in the migration; nothing has to be reconstructed from the EPUB files.

## What was *not* migrated, and why

`read_progress.locator_json` holds per-user locators written under the old convention, and
they are left alone. The reasoning, verified against every reader of the field:

- No consumer inverts `totalProgression` into a page or a position any more. The KOReader
  EPUB path derives its position from the `DocFragment` index and the resource `href`; its
  PDF and DIVINA path takes the page integer from the progress string; `WebPubRoutes` uses
  `locations.position`; `KoboRoutes.resolveKoboPosition` uses the `koboSpan` and
  `progression`. A stale value can therefore only be displayed, never turned back into
  somebody's place.
- It is overwritten by the device's next sync.
- It is client-authored JSON from four writers with different shapes. Rewriting it in SQL
  would not be reversible, unlike the analyzer column, whose inputs survive the migration.

`read_progress.page` is unaffected: a page number means the same thing before and after.
A KOReader user's already-stored page can sit up to about one page from where the new
mapping would put it, which is the bounded error ADR 0105 described, and it resolves on
their next sync.

## Consequences

- A position's `totalProgression` is now what its name says, and the pair
  `(progression, totalProgression)` is coherent: the first position of a freshly opened
  book reports `0` and `0`.
- **A Kobo device is told a finished book is `(n - 1) / n` rather than 100%.** This follows
  from the convention — a locator addresses the start of a location, so no locator can
  express "the end" — and it is what Komga reports too. `StatusInfo.Status` remains the
  authority for completion and still reads `Finished`. Deliberately not special-cased:
  reporting `100` for a completed book would put a second convention in the field that this
  ADR exists to make single-valued, and Kobo's own status field already carries the fact.
- A client that hard-coded `0.5`/`1.0` for a two-position book sees each position move back
  by `1 / count`. The first-party reader echoes the server and is unaffected.
- The round trip is now covered where ADR 0105 said it was not: `KoreaderSyncRoutesTest`
  syncs a position, reads it back, and syncs the returned progress string again, asserting
  the stored page at **both** ends — the first position and the last — because the last is
  where the old arithmetic's accidental cancellation was doing the work.
- `pageFor` and the locator convention are no longer coupled, so the next change to either
  is a local one. That, and not the arithmetic, is the durable part of this ADR.
