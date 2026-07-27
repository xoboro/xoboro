# ADR 0035: Shared media artwork

## Context

Poster operations exist for Komga books, series, collections, and read lists.
Xoboro also needs the same capability for future Novel, Book, Video, and Audio
media without cloning Comic-specific thumbnail tables and lifecycles.

Uploaded images are untrusted. Reading them without input, dimension, and
decoded-pixel limits permits excessive memory use. Selection changes also need
to be atomic so an owner never has two selected posters.

## Decision

- Model artwork with a generic owner kind and ID. A Komga Book maps to the
  canonical `MEDIA_ITEM` owner; series and organization owners remain grouping
  adapters.
- Persist metadata and normalized bytes together in SQLite. Enforce one
  selected artwork row per owner with a partial unique index and transactional
  selection changes.
- Limit multipart uploads to 20 MiB, inspect dimensions before decoding, cap
  decoded pixels, resize the longest dimension to 1600 pixels, flatten alpha,
  and encode durable JPEG output.
- Allow only user-uploaded artwork to be deleted through compatibility routes.
- Apply catalog authorization and content restrictions before every read.
  Mutations additionally require an administrator.
- When no durable selection exists, derive a response from the first visible
  book page. This keeps existing analyzed libraries usable while durable
  generated-artwork tasks are added later.

## Consequences

All current poster owners share one tested lifecycle and future media variants
can use `MEDIA_ITEM` artwork directly. Owner isolation prevents a thumbnail ID
from leaking across routes. Generated and sidecar artwork ingestion, cache
validators, event publication, and exact Komga image settings remain required
before differential compatibility certification.
