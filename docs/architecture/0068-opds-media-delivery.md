# ADR 0068: OPDS media delivery

- Status: accepted
- Date: 2026-07-27

## Context

The REST media routes already matched Komga's shallow validators and avoided
opening remote content when `If-Modified-Since` proved a page unchanged. OPDS
v1 and v2 still had independent streaming loops without validators or Komga
content dispositions.

The protocols also expose different page numbering. OPDS v1 URLs are
zero-based and Komga adds one before reading a Book page, while OPDS v2 uses
the canonical one-based number. Xoboro previously treated both as one-based.

## Decision

- Translate OPDS v1 page numbers from zero-based URLs to canonical one-based
  media indexes and keep OPDS v2 unchanged.
- Apply analyzed-media `Last-Modified` checks before opening page content.
- Reuse the shared one-pass cache body and exact Spring shallow ETag algorithm
  for OPDS pages and Book thumbnails.
- Preserve Komga's inline UTF-8 content disposition for page responses.
- Remove the duplicated 64 KiB OPDS streaming loops.
- Add authenticated synthetic coverage for protocol numbering, validators,
  timestamp-first source bypass, dispositions, and stream closure.
- Extend the digest-pinned Komga 1.25.0 media differential suite with OPDS v1
  pages, OPDS v2 pages, and the common thumbnail route.

## Consequences

Multi-page OPDS v1 readers no longer receive the following page by mistake.
Unchanged remote pages can return `304` without opening NAS or WebDAV content,
and shallow revalidation uses a single source read when an ETag must be
calculated.

The live media suite now covers eleven authenticated cases. OPDS feed XML/JSON
envelopes, failure bodies, and acquisition downloads remain separate
certification work.
