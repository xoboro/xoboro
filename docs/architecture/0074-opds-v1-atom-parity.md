# ADR 0074: Certify OPDS v1 Atom and OpenSearch Parity

- Status: accepted
- Date: 2026-07-28

## Context

The OPDS v1 routes returned catalog data, but their hand-written XML was not a
faithful Komga representation. Strict parsers rejected the Atom declaration
because it was preceded by indentation, feed paging links were absent, and
book entries used different titles, content, acquisition URLs, and media
metadata. The advertised OPDS archive acquisition path was also not routed.

The differential runner could compare JSON, text, and bytes, but could not
compare XML without treating insignificant namespace prefixes and formatting
as differences.

## Decision

- Generate valid Atom and OpenSearch documents without leading bytes before
  the XML declaration.
- Match Komga feed and entry identifiers, timestamps, authorship, empty
  content, filtered self links, previous/next links, and navigation media
  types.
- Match acquisition entries, including archive size text, authors, full and
  small artwork, wildcard archive download, and OPDS-PSE stream metadata.
- Preserve manual collection/read-list order and use Komga's default sorting
  when those resources are unordered.
- Add a namespace-aware canonical XML comparison mode to the live
  differential runner. Reject DTDs, external entities, and external schema
  access before normalization.
- Compare every OPDS v1 feed category, search/filter and edge paging behavior,
  missing resources, and archive acquisition against the digest-pinned Komga
  1.25.0 image.
- Omit series publisher from DiViNa publication metadata because Komga does
  not project that series field into its WebPub manifest.

## Consequences

Strict OPDS clients can parse the generated documents, follow every advertised
archive and page link, and page through feeds with Komga-compatible semantics.
The dedicated live suite covers 23 Atom/OpenSearch and failure cases; the
shared media suite covers 12 binary/manifest cases including OPDS archive
acquisition.

Generated identifiers, origins, and timestamps are ignored only at their
specific XML paths. All remaining elements, text, attributes, namespaces, and
ordering are compared structurally.

The cross-cutting security/error response block in ADR 0075 subsequently
completed the unauthorized response body and exact `WWW-Authenticate`
formatting.
