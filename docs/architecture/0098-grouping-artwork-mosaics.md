# 0098 — Grouping artwork mosaics and staleness

## Status

Accepted. WebP output is deferred pending a dependency decision; see Consequences.

## Context

The `Artwork` coverage row listed three remaining items: a tiled composite of several member covers,
WebP output from the generator, and re-triggering generation when a grouping's membership changes.

The starting point (#135) gave a collection or read list the cover of its **first** member that had one.
That closed the visible gap — a group with no cover at all — and the row said so rather than calling it
a mosaic.

## Decision

### 2×2 only, and a single cover below four

`TiledArtworkComposer` tiles four covers and returns the first cover unchanged when it has fewer.

The reason is **aspect ratio**, not simplicity. Halving both the width and the height of a cover-shaped
canvas leaves each cell cover-shaped, so a 2×2 mosaic sits in a grid of covers without looking wrong. A
1×2 strip or a 1×3 row does not — it would be the only differently-proportioned thing on the shelf.

Forcing a mosaic from two or three covers by leaving quadrants blank is worse than either: an empty
quadrant reads as a *failed image*, not as a design. So a three-member group shows one member's cover.
That is a deliberate floor.

The mosaic's own ratio comes from the first cover, so it is shaped like the covers it is made of rather
than always square.

### One unreadable member must not cost the group its cover

A cover that fails to decode is skipped. If skipping drops the count below four, the composer falls back
to the first *usable bytes* rather than the first successfully decoded image — so the outcome is
identical to the not-enough-covers case, including when nothing decoded at all. A group has several
members precisely so one bad file can be tolerated.

No decoded-pixel guard is applied here. These bytes are artwork this server already stored, having
passed `SafeJpegArtworkProcessor`'s limits on the way in. Repeating the guard would imply this is a
boundary where untrusted bytes arrive, and it is not.

### Collection walks stop at four covers

A group can hold thousands of members, and covers past the fourth cannot appear in the result. Reading
them would be work with no possible effect.

### Staleness is two timestamps, not new state

The sweep now enqueues a grouping when it has no generated artwork **or** when its `updatedAtMillis` is
newer than the newest generated artwork it has. Membership changes bump that timestamp, so a stale cover
is re-derived without any of the three mutation paths — native API, compatibility API, metadata import —
having to know this task exists.

The alternative was to record which members a cover was derived from. That is new state to migrate, keep
consistent, and get wrong; two timestamps the domain already keeps are enough.

**Newest, not oldest.** A regeneration replaces the previous generated artwork, so the newest one is the
cover currently in use and the only one whose age says anything.

**`>`, not `>=`.** A grouping created and covered within the same millisecond must not re-enqueue
forever, and a real membership change always lands after the cover it invalidates. A test pins each
direction, and reversing the comparison fails one of them.

## Consequences

- A four-member group with four available covers gets a mosaic; smaller groups keep the borrowed cover.
  The coverage row now says "2×2 mosaic when four member covers are available" rather than implying a
  general composite.
- A membership change is picked up by the next sweep, so the coverage row's "generation is not
  re-triggered when membership changes" is closed. It is picked up *by a sweep*, not immediately — the
  row says so.
- **WebP output is not implemented, and is blocked rather than skipped.** The JDK ships no WebP
  `ImageIO` writer — verified by enumerating `ImageIO.getWriterFormatNames()` on the toolchain JDK,
  which lists only BMP, GIF, JPEG, PNG, TIFF and WBMP. Emitting WebP therefore requires a new
  dependency, and dependencies are not added unilaterally in this project. The coverage row records it
  as a dependency decision awaiting an answer, not as work nobody got to.
- `TiledArtworkComposer` emits JPEG, matching what `SafeJpegArtworkProcessor` consumes: the composed
  bytes go straight back through it for scaling and re-encoding. This component decides layout only,
  which is also why its tests compare pixels with a tolerance — asserting exact values would be
  asserting the JPEG encoder's quality setting.
