# 0096 — Suppressing continuation volumes of a multi-volume archive

## Status

Accepted.

## Context

A multi-volume RAR set is one logical archive split across several files. Only the first volume opens as
an archive; the rest are continuations. `.rar` and `.cbr` are both scanned extensions, so every
continuation volume became its own book with a media item that could never be read. The coverage row
recorded it as "suppressing trailing volumes at inventory is still missing".

The RAR 2 scheme names continuations `.r00`, `.r01`, `.s00`. Those extensions are not scanned, so those
volumes never became candidates — an exclusion that was **incidental**, not intentional, and is now
stated where the rule lives. What actually reached the catalog was the RAR 3+ scheme:
`name.part1.rar`, `name.part2.rar`, …

## Decision

### The name alone is not enough

The obvious rule — "drop anything matching `.partN` where N > 1" — makes real books disappear. A comic
titled `Series - part 2.cbr` is a whole book. And a lone `x.part2.rar` with no `x.part1.rar` beside it
is far more likely an oddly named book than half a set.

The asymmetry decides it: **wrongly suppressing is worse than wrongly keeping.** A suppressed book is
invisible — the user cannot tell it from a file that was never there. A kept continuation volume is
visible and reports `ERR_1101`/`ERR_1102`, which at least tells them something is wrong. So the rule
requires two independent conditions:

1. The name matches the **tool-generated** shape. `RarVolumeNames` requires `.partN.` immediately after
   a character that is not whitespace, `.`, `_` or `-`, so `Series - part 2.cbr`, `Series part 2.cbr`,
   `Series_part2.cbr` and `Series..part2.cbr` are all left alone.
2. **The first volume is staged in the same directory.** A set lives in one directory, so two unrelated
   series that each have a `part1`/`part2` pair in different folders do not interact.

A zero-padded number is the same volume as its unpadded form — `part01` is how a set of ten or more
volumes is written, so the numeric value identifies the position and `part1`/`part01` are one volume.
The extension stays in the stem, so a `.part1.rar` and a `.part2.cbr` are not treated as one set: no
tool produces a mixed-extension set, and treating them as related would only let one oddly named file
suppress another.

### The decision runs over the staged set, not the stream

`CatalogScanner` streams candidates into batches of 500 and stages them. The sibling check needs the
whole set, and `Files.walkFileTree` does not deliver one directory's files contiguously — a directory's
entries are interleaved with entire subtrees of its subdirectories. Buffering to make the check possible
in the callback would mean holding the entire library in memory.

So the check runs after everything is staged and before the session completes, against the staging
table, which is where the whole set already lives at no memory cost.

### The rule stays in Kotlin, not in SQL

`CatalogReconciliationStore.stagedVolumeCandidatePaths` narrows with a deliberately **looser**
`LIKE '%.part%'` than the parser accepts, and `CatalogScanner` decides using `RarVolumeNames`. The
`LIKE` must not miss a name the parser would accept; over-selecting costs a few extra strings to parse.
Encoding the naming rule in SQL as well would put it in two places that could disagree, and the version
in SQL would be the one nobody tested.

Both new store methods are **abstract**, not defaulted. A default `stagedVolumeCandidatePaths` returning
nothing would silently disable suppression in any implementation that forgot to override it, and there
are only two implementations.

### Suppressed volumes are counted as ignored

They go into the scan's `ignoredFiles` total rather than disappearing from every count. A file the
server decided not to index should still be visible as a decision.

## Consequences

- One `.partN.rar` set yields one media item instead of N, and the surviving item is the one that can
  actually be opened.
- Multipart handling in the analyzer is unchanged and still reports `ERR_1102` for a set with a missing
  volume. That path remains unverified against a real volume set — no dependency or assumed tool on the
  build machine can write one — and the coverage row still says so. This change reduces how often a
  user meets that path; it does not verify it.
- Verified by mutation: suppressing without requiring the first volume fails both the lone-continuation
  and the different-directories tests, and grouping without the directory fails the latter.
