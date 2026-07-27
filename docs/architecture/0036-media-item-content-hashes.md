# ADR 0036: Media-item content hashes and duplicate pages

- Status: accepted
- Date: 2026-07-27

## Context

Komga detects duplicate files with a whole-file hash and duplicate pages with
hashes for a configurable number of leading and trailing pages. Re-reading and
re-downloading remote content in the query path would make a large NAS library
unnecessarily expensive.

Xoboro's domain root is `Library` to `MediaItem`, specialized as Comic, Novel,
Book, Video, and Audio. Hashing therefore cannot become a new Comic-only root
even though the initial Komga compatibility routes expose Book-shaped DTOs.

## Decision

- Treat content hashes as analysis artifacts owned by `MediaItemId`.
- Use Komga-compatible seeded XXH3-128 hashes. Normalize JPEG pages by decoding
  and re-encoding before hashing so metadata-only differences do not produce
  false negatives.
- Hash only the first and last three Comic pages when library page hashing is
  enabled. Persist hashes with the analyzed page rows and index non-empty
  values.
- Compute a whole-file hash during the existing source materialization when
  file hashing is enabled. Remote sources therefore do not require another
  download for the file hash.
- Aggregate duplicate files and pages directly in SQLite with stable,
  allowlisted sorting and bounded paging. Persist administrator decisions in a
  separate known-hash table.
- Keep Komga Book and page-hash DTOs in the compatibility module. The
  repository and match identity use `MediaItemId`, allowing later Video frame
  hashes and Audio fingerprints to share the analysis boundary without
  inheriting Comic routes.

## Consequences

Duplicate listing is proportional to indexed database rows instead of source
bytes. Enabling page hashing still performs the unavoidable reads for selected
pages, but it does not hash every page or load an entire archive into memory.
Physical duplicate-page removal remains a source mutation workflow and must
not be enabled until local and remote adapters provide atomic replace
semantics.
