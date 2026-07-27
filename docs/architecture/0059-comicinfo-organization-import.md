# ADR 0059: ComicInfo organization metadata import

- Status: accepted
- Date: 2026-07-28

## Context

ComicInfo parsing covered Komga's bibliographic Book and Series fields, but
ignored the fields that organize catalog entries:

- `AlternateSeries`, `AlternateNumber`, `StoryArc`, and `StoryArcNumber` define
  read-list membership;
- `SeriesGroup` defines collection membership.

The Library already exposed independent ComicInfo switches for Book, Series,
ReadList, and Collection imports. Treating the bibliographic switches as a
prerequisite made the latter two switches ineffective.

## Decision

- Carry read-list and collection requests alongside provider metadata patches.
- Let metadata providers state independently whether their bibliographic patch
  should be applied.
- Parse ComicInfo organization fields with Komga's comma splitting, integer
  validation, alternate-series, and story-arc association rules.
- Apply organization requests through a dedicated persistence port.
- Match names case-insensitively, create missing organizations, ignore duplicate
  membership, and publish organization events only after a committed change.
- Preserve valid ComicInfo read-list numbers as sparse positions. If a requested
  position is already occupied, append after the greatest existing position.
- Keep Book, Series, ReadList, and Collection import switches independent.

## Consequences

Durable metadata jobs now create and extend Komga-compatible read lists and
collections without enabling unrelated bibliographic imports. Reprocessing the
same archive is idempotent. Sparse and colliding positions retain deterministic
order, and all changes flow through the existing SSE organization event bridge.
