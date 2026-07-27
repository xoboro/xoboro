# ADR 0063: Synthetic catalog differential certification

- Status: accepted
- Date: 2026-07-28

## Context

The anonymous live differential suite cannot exercise scanning, media analysis,
catalog metadata, or authenticated page envelopes. A useful catalog baseline
must use the same source path on both servers without committing personal media
or accepting broad response exclusions.

The first live synthetic comparison exposed observable differences in 64-bit
aggregate timestamps, natural Book numbering, EPUB compatibility flags, local
source paths, nullable fields, sort envelopes, and alphabetical-group casing.

## Decision

- Generate a deterministic two-page synthetic CBZ during CI and mount its
  absolute root into the digest-pinned Komga reference.
- Claim both fresh servers with CI-only synthetic credentials, create
  equivalent disabled-schedule libraries, and wait until both Book media
  records are `READY`.
- Compare Library, Series, Book, and alphabetical-group responses
  structurally.
- Ignore only independently generated identifiers and timestamps. Keep
  settings, counts, metadata, media analysis, numbering, source paths, nullable
  fields, page envelopes, and grouping values strict.
- Preserve Komga's case-insensitive natural series numbering while processing
  at most 500 Series identifiers per reconciliation query.
- Persist and project analyzed EPUB capability flags instead of deriving them
  from a broad media profile.
- Keep the fixture and every catalog name synthetic.

## Consequences

Every relevant pull request now proves that a real archive follows the same
scan-to-catalog contract on Komga 1.25.0 and Xoboro. Generated values cannot
hide semantic drift, and large scans do not require all scanned Books to be
loaded by one numbering query. Media delivery, metadata sidecars, errors, and
organization resources still require separate authenticated suites.
