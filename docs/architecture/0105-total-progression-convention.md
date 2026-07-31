# 0105 — `totalProgression` is one position ahead of Readium, and stays that way for now

## Status

Accepted, with the correction deferred and its precondition named.

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

Established rather than assumed, because the blast radius is what decides whether
this is urgent:

- **Resume is unaffected.** `EpubReader` resumes through
  `resumePage(readProgress, order.length)`, which works from the stored page /
  position. No client places a reader by `totalProgression`, so nothing lands on
  the wrong chapter.
- **Kobo's reported percentage is affected.** `KoboRoutes` sets
  `ProgressPercent = totalProgression * 100`, so a Kobo device opening chapter one
  of a two-chapter KEPUB is told 50%. On a twenty-chapter book it is 5% — smaller,
  still wrong. `KoboRoutes` also builds its own locator from a stored position, so
  the same skew is in what it reports back.

So the defect is confined to a reported percentage. It misinforms; it does not lose
anyone's place.

## Decision

**Leave the arithmetic alone on this branch. Do not paper over it in clients.**

The deciding factor is not the compat surface — it is the stored data. Positions
are persisted, and they are recomputed only when a book is analyzed. Changing the
formula would leave every already-analyzed EPUB on the old convention and every
newly analyzed one on the new, in the same table, with no way to tell them apart:
a mixed-semantics database, which is worse than a consistent documented skew. That
conflicts directly with this project's rule that migrations be backward
compatible, because the reconciling migration is not a schema change at all — it
is a forced re-analysis of every EPUB in every library.

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

1. the analyzer formula;
2. a re-analysis of existing EPUBs, because stored positions do not migrate
   themselves — on a large library this is the expensive part and the reason this
   is not a drive-by fix;
3. `EpubMediaAnalyzerTest.kt:53`, which asserts the last position's
   `totalProgression` is `1F` and so pins the current convention — under the
   correction the last of two positions is `0.5` — plus the delivery-route fixture;
4. a decision about Kobo devices that have already synced a percentage under the
   old convention and will see it move backwards.

Item 4 is the one that makes this a product decision rather than a bug fix, and
item 2 is what makes it a task of its own.

## Consequences

- A reader or client reading these values should treat `totalProgression` as "how
  far through the publication this position ends", which is what it is.
- Nobody's reading position is at risk; only a displayed percentage is wrong.
- The next person to look at this finds the arithmetic, the reason, and the list
  above, instead of rediscovering it from a test fixture that disagreed with
  production.
