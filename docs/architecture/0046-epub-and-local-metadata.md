# ADR 0046: EPUB and local metadata ingestion

## Status

Accepted.

## Context

EPUB metadata lives in the package document rather than ComicInfo.xml. Comic
and ebook libraries also commonly store book and series artwork beside media
files. Both inputs are untrusted, source-specific, and refreshable, so they
must obey metadata locks, catalog access boundaries, and artwork safety rules.

## Decision

- Resolve the EPUB package through the bounded `container.xml` path and reject
  paths that escape the archive.
- Import main title, description, date, creators/contributors and roles,
  subjects, validated ISBN, absolute links, publisher, language, series title,
  and group position.
- Support EPUB 3 refinements and Calibre series fallbacks. Feed the result into
  the ordered metadata-provider merge so manual field locks remain authoritative.
- Discover book artwork by a case-insensitive media basename with an optional
  numeric suffix. Discover series artwork from `cover`, `default`, `folder`,
  `poster`, and `series` stems.
- Read sidecars through a source adapter with root containment and byte limits,
  then reuse decoded-pixel validation and JPEG normalization.
- Atomically replace all sidecar artwork for an owner. Prefer sidecars over
  generated artwork, preserve explicit user-upload selection, and fall back to
  another candidate if selected sidecars disappear.
- Skip an independently corrupt or unsupported sidecar without blocking valid
  candidates or metadata refresh.
- Chain each successful incremental analysis to exactly one book refresh and
  one series refresh task. Unchanged scan candidates do not re-enter analysis
  or metadata ingestion.

## Consequences

EPUB metadata and local artwork are restart-safe and share the existing
metadata/artwork models. Remote sources can implement the discovery interface
without exposing filesystem paths. WebP discovery is recognized, but actual
decoding remains dependent on an installed ImageIO reader.
