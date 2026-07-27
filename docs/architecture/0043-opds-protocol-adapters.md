# ADR 0043: OPDS protocol adapters over the media catalog

- Status: accepted
- Date: 2026-07-27

## Context

Komga exposes OPDS 1.2 Atom feeds and OPDS 2 JSON feeds outside its generated
OpenAPI document. Existing readers depend on those routes, media types,
acquisition links, authentication discovery, thumbnails, pages, WebPub
manifests, and Readium progression. Xoboro must preserve that wire contract
without making OPDS another owner of catalog or progress state.

## Decision

- Implement OPDS as a compatibility adapter over `CatalogReadRepository`,
  `BookContentAccess`, shared artwork, and durable read progress.
- Keep Komga's protocol-facing catalog and authentication identity so existing
  clients do not need migration-specific behavior. Xoboro remains the product
  identity outside this compatibility surface.
- Generate absolute links from the current request origin and configured base
  path. Acquisition links target the shared bounded original-file route.
- Apply catalog access before pagination and before collection or read-list
  projection.
- Reuse the WebPub manifest and progression implementation for OPDS aliases
  rather than duplicating profile or locator behavior.
- Stream page and thumbnail bytes through shared content boundaries; never
  buffer complete books in an OPDS route.

## Consequences

OPDS v1 and v2 share authorization, media analysis, artwork, and progress with
the REST API. Comic, Book, and Novel adapters can publish immediately when
their media profiles are supported, while future Video and Audio protocols can
remain separate from the OPDS compatibility layer. Differential response and
failure fixtures are still required before declaring exact compatibility.
