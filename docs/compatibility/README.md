# Komga 1.25.0 compatibility inventory

This directory defines the measurable release boundary for the Xoboro backend.
The compatibility target is Komga 1.25.0.

## Locked REST baseline

The official Komga 1.25.0 OpenAPI document is vendored as a test-only,
checksummed contract snapshot. Automated verification currently locks:

- 130 REST paths
- 165 HTTP operations
- 167 wire schemas
- the exact upstream version and SHA-256

Thirty-nine operations have an Xoboro implementation in progress. They remain
partial until differential response, authorization, validation, persistence,
and error-envelope tests pass against the reference behavior. The other 126
operations are unimplemented.

## Additional compatibility surfaces

OpenAPI does not cover the entire server. Separate inventories and contract
suites are required for:

- [66 non-REST protocol operations](protocols.md) covering OPDS v1/v2, Kobo,
  KOReader, Server-Sent Events, and OAuth2 browser callbacks
- media container parsing and byte-range delivery
- filesystem and scheduled lifecycle behavior
- metadata import, aggregation, locking, and sidecars
- database migration and operational configuration

No percentage may be reported as achieved from source line count or endpoint
presence alone. A capability becomes compatible only after its observable
behavior is covered by automated contract tests.
