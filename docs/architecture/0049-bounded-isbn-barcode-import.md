# ADR 0049: Bounded ISBN barcode metadata import

- Status: accepted
- Date: 2026-07-27

## Context

Komga can derive an ISBN from EAN-13 barcodes near the front or back of a
page-based book. Xoboro needs the same metadata behavior without turning the
general metadata pipeline into an unbounded image scan or coupling canonical
`MediaItem` identity to the Komga Book API.

## Decision

- Implement barcode import as an ordered `BookMetadataProvider`. Existing field
  locks remain the only authority that decides whether the patch is applied.
- Enable the provider only for libraries with `importBarcodeIsbn`. EPUB is
  excluded because its package metadata is authoritative and scanning rendered
  text pages would be wasteful.
- Inspect at most six unique pages in Komga order: the final three in reverse,
  then the first three.
- Ask the shared content boundary for a PNG capped at 4,096 pixels on its
  longest edge. Image conversion uses source subsampling before decode, avoiding
  a full-resolution intermediate when a bounded representation was requested.
- Decode only EAN-13, accept only the ISBN allocation prefixes 978 and 979, and
  verify the check digit before producing a normalized 13-digit patch.
- Isolate per-page failures and close every content stream. A damaged page must
  not prevent the remaining bounded candidates from being inspected.

## Consequences

Comic and PDF adapters gain Komga-compatible automatic ISBN discovery without
changing `MediaItemId`, source storage, or the metadata merge model. Novel,
Book, Video, and Audio providers can continue to use the same ordered provider
boundary while supplying format-appropriate metadata extraction.
