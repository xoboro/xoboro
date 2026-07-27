# ADR 0067: PDF page content negotiation

- Status: accepted
- Date: 2026-07-27

## Context

Komga can serve either a rendered image or a raw single-page PDF from the same
Book page endpoint. The choice is not based only on whether `application/pdf`
appears in `Accept`: it filters PDF and image candidates, then applies HTTP
quality, wildcard specificity, parameter count, and original-order precedence.
Clients can disable this behavior with `contentNegotiation=false`.

Xoboro already supported explicit raw PDF page routes and PDF rendering, but
the ordinary page endpoint always returned an image.

## Decision

- Parse and sort all `Accept` values using Ktor's HTTP quality and specificity
  ordering.
- Keep only candidates compatible with `application/pdf` or `image/*`, matching
  Komga's limited negotiation boundary.
- Return a raw single-page PDF only when the highest-ranked relevant candidate
  is PDF-compatible.
- Preserve stable header order when equally specific candidates have equal
  quality.
- Honor `contentNegotiation=false`, reject invalid boolean values, and keep the
  explicit `/raw` route independent of negotiation.
- Select the representation before parsing image conversion parameters, so a
  negotiated raw PDF follows Komga's controller order.

## Consequences

PDF readers can request native single-page documents through the standard page
URL, while browsers preferring a concrete image representation continue to
receive rendered images. Synthetic unit and authenticated route tests cover
empty, unrelated, wildcard, ordered, weighted, disabled, and invalid requests.

Live generated-PDF differential fixtures remain certification work.
