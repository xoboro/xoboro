# Komga 1.25.0 backend parity ledger

This document is the release gate for Xoboro Server 1.0.

## Definition

The target is 99.9% observable backend compatibility with Komga 1.25.0. The
percentage is calculated from weighted, testable behaviors rather than source
line count. A row is complete only after implementation and automated
verification.

The locked Komga 1.25.0 REST inventory contains 130 paths, 165 operations, and
167 schemas. All 165 operations are partially implemented and none are yet
certified by differential tests. Protocol and non-REST inventories are tracked
separately in [`docs/compatibility`](compatibility/README.md).

The non-OpenAPI inventory contains 66 operations, including the two OAuth2
browser-flow routes.

Status:

- `TODO`: not implemented
- `PARTIAL`: some behavior implemented, compatibility incomplete
- `COMPATIBLE`: implementation and all required tests pass
- `INTENTIONAL`: documented incompatibility approved as an Xoboro correction

## Foundation

| Capability | Status | Evidence |
|---|---|---|
| Configuration and environment overrides | PARTIAL | Komga-compatible administrator settings GET/PATCH, durable defaults and nullable overrides, explicit null wire fields, environment/database/effective source reporting, restart-applied port and context path, live worker resizing, live remember-me duration and key rotation, strict port/database/worker/poll/lease/shutdown parsing, and validation tests; full Komga property surface pending |
| SQLite schema and migrations | PARTIAL | Flyway V1-V20 clean-install and V1-to-current upgrade tests |
| Extensible media domain | PARTIAL | Portable `Library` → `MediaItem` sealed hierarchy for Comic, Novel, Book, Video, and Audio; capability-based page/text/timeline/content behavior; canonical `MediaItemId` and repository; durable semantic discriminator and Komga-row migration tests; write lifecycle remains on the Komga catalog adapter |
| Transactions and restart recovery | PARTIAL | WAL, rollback, close/reopen durability, and filesystem scan restart tests |
| Durable prioritized task queue | PARTIAL | Priority/FIFO, deduplication, group exclusion, heartbeat, lease loss/recovery, exponential retry, dead-letter, concurrent claim, scan/analysis routing, active-lease-safe administrator queue clearing, dynamically resizable worker-pool lifecycle, and runtime tests; metrics pending |
| Structured logs, health, readiness, metrics | PARTIAL | Worker failure logging plus `/health` liveness and SQLite-backed `/ready` contract tests; structured fields and metrics pending |
| Server-Sent Events | TODO | |
| Backup and restore | TODO | |

## Media

| Capability | Status | Evidence |
|---|---|---|
| CBZ and ZIP | PARTIAL | Real synthetic archive analysis, durable worker execution, content partitioning, persisted page/file state, exact-entry page streaming, original-file streaming, stable error codes, and restart tests; differential behavior pending |
| CBR, RAR, and RAR5 | TODO | |
| PDF | PARTIAL | PDFBox page analysis, optional crop-box dimensions, bounded page indexing, JPEG rendering, raw single-page PDF extraction, WebPub PDF manifest, source materialization cleanup, and synthetic document tests; encrypted documents, metadata, and differential fixtures pending |
| EPUB 2 and EPUB 3 | PARTIAL | Secure ZIP/container/package parsing, manifest/spine resources, fixed-layout and DiViNa detection, EPUB3 navigation plus EPUB2 NCX fallback, Readium positions, KEPUB span detection, persisted extensions, resource streaming with CSP, WebPub manifest, and synthetic fixed/reflowable tests; complete metadata, KEPUB conversion, and differential fixtures pending |
| Content and extension detection | PARTIAL | Tika entry-content detection and corrupt/empty archive tests; outer signatures and non-ZIP formats pending |
| Natural page ordering | PARTIAL | Komga-compatible case-insensitive natural comparator fixture; RAR/PDF/EPUB pending |
| Page dimensions and media profiles | PARTIAL | Optional ImageIO dimensions, DIVINA profile, 64-bit page sizes, and persistence tests; remaining profiles pending |
| Cover and thumbnail generation | PARTIAL | Durable owner-scoped Book/Series/Collection/ReadList artwork, bounded multipart upload, decoded-pixel safety, JPEG normalization/resizing, atomic selection, uploaded-only deletion, authenticated byte delivery, first-visible-book page fallback, and low-priority bulk Book poster generation with generated-only atomic replacement; sidecars and differential caching pending |
| Page streaming and conversion | PARTIAL | Exact analyzed-entry streaming, EPUB resource streaming, PDF JPEG rendering and raw single-page extraction, raw and zero-based routes, JPEG/PNG conversion, bounded page thumbnails, role and catalog-access enforcement, CSP, and resource-lifetime tests; PDF Accept negotiation and differential caching semantics pending |
| Book, series, and read-list downloads | PARTIAL | Original book download streams source materialization with attachment naming and single-byte-range responses; Series and ReadList ZIPs stream entries without whole-archive buffering with role/access enforcement and collision-safe names; differential archive metadata pending |
| Incorrect extension repair | TODO | |
| CBR/RAR to CBZ conversion | TODO | |

## Libraries and lifecycle

| Capability | Status | Evidence |
|---|---|---|
| Library CRUD and non-overlapping roots | PARTIAL | Komga v1 list/get/create/PATCH/deprecated PUT/delete routes, full settings DTO, selected-library visibility, administrator-only root disclosure and mutation, durable lifecycle wiring, local path validation, duplicate/overlap rejection, and contract tests; non-local source administration and differential tests pending |
| Directory exclusions and media filters | PARTIAL | Atomic exclusion replacement, media-setting round trip, and pre-descent local subtree pruning tests |
| Startup, periodic, manual, and deep scan | PARTIAL | Stable normal/deep scan tasks, strict payload handling, deleted-library no-op, startup emission, fixed-rate scheduling/rescheduling/cancellation from live CRUD, library and target-scoped analysis/metadata REST triggers, bounded local inventory/reconciliation, and real-runtime execution tests; differential tests pending |
| Incremental add/change/move/delete detection | PARTIAL | Set-based initial/idempotent/change/move/restore/delete, ambiguous identity, partial-inventory, real-filesystem, and restart tests; non-local sources pending |
| Unavailable storage handling | TODO | |
| Trash, restore, and empty trash | PARTIAL | Logically deleted books and now-empty deleted series are removed atomically, dependent media cascades, administrator REST requests emit deduplicated high-priority durable jobs, and `emptyTrashAfterScan` schedules cleanup after successful scans; restore API and differential tests pending |
| File, page, and KOReader hashes | PARTIAL | Komga-compatible seeded XXH3-128 whole-file hashes reuse source materialization; optional first/last-three page hashes persist during Comic analysis, JPEG pixels are normalized before hashing, and non-empty hashes are indexed; KOReader hash and differential fixtures pending |
| Book and series lifecycle | PARTIAL | Administrator Book/Series file deletion emits deduplicated, source-aware durable tasks and schedules catalog reconciliation; restore and differential events pending |
| File import, upgrade, and deletion | PARTIAL | Administrator COPY/MOVE/HARDLINK imports and upgrades use canonical root containment, validated destination leaves, sibling temporary files, atomic replacement, retry-safe deletion, and durable tasks; non-local mutation adapters pending |
| Filesystem and transient import discovery | PARTIAL | Administrator filesystem listing hides dot entries and validates absolute directories; session-scoped transient CBZ/ZIP, EPUB, and PDF discovery rejects Library overlap, reuses production analysis, and streams previews; differential envelopes and remote import pending |
| Duplicate file detection | PARTIAL | Administrator Book route groups non-empty equal file hashes and sizes in SQLite with stable paging and Komga DTO projection; differential sorting pending |
| Duplicate page detection and removal | PARTIAL | Known/unknown/match queries, action updates, live bounded thumbnails, SQL aggregation/paging, restart persistence, administrator routes, highest-priority per-Book jobs, atomic local CBZ rewriting, deletion counters, and scan reconciliation are implemented; non-local mutation and automatic policy execution remain pending |

## Metadata

| Capability | Status | Evidence |
|---|---|---|
| ComicInfo.xml import | PARTIAL | Bounded, secure root-entry CBZ parsing imports book and series fields through source-aware providers; synthetic field, malformed-input, setting, persistence, durable-job, and runtime tests; complete field mapping, collections, read lists, EPUB, and differential fixtures pending |
| EPUB metadata import | TODO | |
| Local artwork | TODO | |
| Mylar metadata | PARTIAL | Source-aware bounded `series.json` import supports textual/formatted descriptions, title, publisher, year/volume naming, status, age rating, and issue count with synthetic tests; complete schema and differential fixtures pending |
| Series sidecars, including series.json | PARTIAL | Canonical-root containment, traversal and size guards, setting-aware provider selection, durable per-library refresh, and real-runtime persistence tests; artwork sidecars and remote sources pending |
| One-shot detection | PARTIAL | Configured-directory, root-series, and candidate derivation tests; metadata aggregation pending |
| ISBN barcode detection | TODO | |
| Series aggregation | PARTIAL | Transactional path grouping, book counts, restore/delete, partial-scan safety, ordered metadata providers, and lock-preserving book/series merge tests; full Komga aggregation precedence pending |
| Field locks and manual patches | PARTIAL | Durable normalized lock sets, refresh-time lock preservation, presence-sensitive book/series REST patches, explicit-null clearing, administrator authorization, and persistence/route tests; differential validation and event emission pending |
| Bulk metadata updates | PARTIAL | Komga-compatible book-ID-to-patch map skips missing targets and updates valid metadata; aggregation events and differential validation pending |
| Multiple thumbnails and selection | PARTIAL | Shared artwork lifecycle persists multiple owner-scoped candidates with one selected row, restart durability, owner isolation, administrator mutation, and all 24 Komga poster operations; generated/sidecar ingestion and event emission pending |

## Catalog and organization

| Capability | Status | Evidence |
|---|---|---|
| Series, books, and one-shots | PARTIAL | Upgrade-safe complete scan-state entities, metadata relations, database-paged read model, Komga DTOs, detail and series-book REST routes, and transactional/integration tests; read progress and differential tests pending |
| Full-text search | TODO | |
| Filtering, sorting, and alphabetical groups | PARTIAL | Allowlisted SQL sorting, escaped full-text matching, legacy library/publisher/language/genre/tag filters, stable pagination, alphabetical groups, and SQL-level referential filters with authorization/content restrictions; recursive search conditions and differential tests pending |
| Latest, new, updated, on-deck, keep-reading | PARTIAL | Book latest/on-deck and Series latest/new/updated use explicit database sorts, access filtering, and durable per-series progress aggregates; keep-reading pending |
| Collections and manual ordering | PARTIAL | Durable ordered membership, case-insensitive unique names, transactional CRUD, access-filtered list/detail/reverse-membership routes, filtered indicators, member pagination, cascade behavior, and REST integration tests; thumbnails, metadata import, and differential filters pending |
| Read lists and manual ordering | PARTIAL | Durable ordered membership and summaries, transactional CRUD, access-filtered list/detail/book/sibling/reverse-membership routes, filtered indicators, cascade behavior, archive streaming, Mihon progress adapters, and REST integration tests; differential filters pending |
| ComicRack CBL import | PARTIAL | Bounded XXE-safe CBL parsing, exact Komga error codes, duplicate-name reporting, volume aliases, and chunked set-based case/leading-zero-insensitive matching with route and persistence tests; differential fixtures pending |
| Historical events | PARTIAL | Durable event/property transactions, stable allowlisted paging, batched property hydration, administrator REST projection, and source file import/deletion event emission; complete Komga event coverage and differential timestamp behavior pending |
| Referential authors, genres, tags, languages, publishers | PARTIAL | Komga v1/v2 author, role/name, genre, sharing-label, book/series/combined tag, language, publisher, age-rating, and release-year routes use direct distinct SQL with access restrictions before paging/aggregation; differential ordering and edge filters pending |

## Users and security

| Capability | Status | Evidence |
|---|---|---|
| Initial administrator claim | PARTIAL | Anonymous GET status and header-based POST claim contracts, atomic single-winner persistence, all-role DTO, TSID identity, BCrypt hashing, null omission, validation, and real-runtime restart tests; automated initial-user configuration pending |
| Multi-user CRUD and password reset | PARTIAL | Komga v2 current-user, admin list/create/patch/delete, self/admin password change, duplicate/validation/not-found behavior, durable relations, and immediate credential replacement tests; demo-mode and exact global error envelopes pending |
| Roles and authorization | PARTIAL | Komga role model, durable assignment, implicit USER authority, administrator-only management, self-mutation guards, and 401/403 tests; authorization across remaining APIs pending |
| Library restrictions | PARTIAL | Admin, all-library, and selected-library access semantics, persistence tests, and SQL-level Book/Series catalog enforcement; remaining interfaces pending |
| Age and sharing-label restrictions | PARTIAL | Komga-compatible normalization, allow/exclude precedence, evaluation, persistence, and pre-pagination SQL catalog enforcement tests; remaining interfaces pending |
| Sessions and remember-me | PARTIAL | Seven-day in-memory inactivity sessions, SHA-512 token-digest storage, cookie/header transport selection, session reuse and touch, header-to-cookie conversion, GET/POST logout, expiry, and security-change revocation; Spring-compatible signed remember-me tokens, durable and live-rotatable server key, dynamically configurable cookie duration, password invalidation, restored-session issuance, and logout cleanup tests; OAuth pending |
| Basic authentication | PARTIAL | Komga realm challenge, missing/malformed/unknown/wrong credential rejection, case-insensitive success, and `/api/v2/users/me` tests; remaining protected routes pending |
| API keys | PARTIAL | Dashless UUID generation, TSID IDs, ten-attempt collision handling, Komga SHA-512 storage, redacted list, duplicate comment, owner deletion, cascade, and `X-API-Key` authentication tests; demo mode and protocol-specific key transports pending |
| OAuth2 providers | PARTIAL | Anonymous provider discovery, Spring-compatible registration environment binding, authorization-code redirect and token exchange, one-time state plus browser-bound CSRF correlation, OIDC nonce and RSA ID-token verification, issuer/audience/authorized-party/time validation, verified-email policy, GitHub primary verified-email fallback, existing-account matching, optional account creation, Komga error codes, session issuance, failure redirects, and login activity source tests; EC-signed ID tokens, issuer discovery, forwarded-origin policy, and differential provider tests pending |
| Authentication activity | PARTIAL | Durable success/failure ledger, password and API-key source recording, credential-safe failed-key fingerprinting, User-Agent/IP capture, current-user/admin pagination and sorting, API-key-filtered latest lookup, retention cutoff, cascade cleanup, and restart tests; sessions, OAuth, demo mode, proxy IP policy, and scheduled cleanup pending |
| Per-user client settings | PARTIAL | Global and per-user namespaced string settings, anonymous visibility filtering, administrator authorization, user isolation, transactional upsert, selective deletion, cascade cleanup, restart persistence, validation, and six REST operation tests |
| Announcements | PARTIAL | Komga JSON Feed retrieval with one-hour expire-after-access cache, single-flight refresh, unknown-field tolerance, administrator authorization, per-user durable read flags, duplicate-safe marking, user cascade cleanup, empty-feed 404 and upstream-error propagation, restart persistence, and two REST operation tests |

## Reading

| Capability | Status | Evidence |
|---|---|---|
| Page-based progress | PARTIAL | Durable per-user page/completed/read-date/device rows, transactional series aggregates, page-bound validation, Book DTO projection, and REST integration tests; device mutation, SSE, and differential tests pending |
| R2 locator and EPUB progression | PARTIAL | Durable per-user Readium locator/device/timestamp state, stale-write conflict rejection, authenticated GET/PUT contracts, persisted EPUB positions, position-list contract, and DiViNa/EPUB integration tests; advanced locator reconciliation and differential fixtures pending |
| Mark read and unread by book or series | PARTIAL | Book PATCH/DELETE and set-based Series POST/DELETE routes plus Mihon ReadList index and Series number-sort adapters enforce catalog access and update durable aggregates; bulk and differential adapters pending |
| Previous and next book semantics | PARTIAL | Stable metadata number, relative-path, and ID tie-breaking in SQL with access checks and boundary tests; differential and read-state semantics pending |
| Sync points and conflict behavior | PARTIAL | Durable user/API-key-scoped sync points, restart persistence, foreign-key cleanup, and authenticated complete or selected-key deletion; Kobo creation/conflict behavior pending |

## Interfaces and protocols

| Capability | Status | Evidence |
|---|---|---|
| Komga REST API v1/v2 | PARTIAL | All 165 pinned operations have an implementation in progress, including management info, font resources, release discovery, durable poster regeneration, and atomic duplicate-page removal; differential certification and exact schema/error completion pending |
| Komga authentication and session semantics | PARTIAL | Basic, `X-API-Key`, `KOMGA-SESSION`, `X-Auth-Token`, and `komga-remember-me` authentication, multi-provider principal propagation, seven-day inactivity, 365-day remember-me restoration, transport conversion, logout, credential deletion, password-signature invalidation, and security-change invalidation tests; OAuth pending |
| OpenAPI document | TODO | |
| OPDS v1 | TODO | |
| OPDS v2 and authentication document | TODO | |
| Kobo Sync and KEPUB | TODO | |
| KOReader Sync | TODO | |
| SSE event contracts | TODO | |

## Migration and operations

| Capability | Status | Evidence |
|---|---|---|
| Read-only Komga 1.25.0 database importer | TODO | |
| Users and restrictions migration | TODO | |
| Metadata and locks migration | TODO | |
| Progress migration | TODO | |
| Collections and read-list migration | TODO | |
| Docker amd64 and arm64 | TODO | |
| Compose deployment | TODO | |
| Reverse-proxy and base-path support | PARTIAL | Restart-applied, database- or environment-configured base path mounts all current routes; forwarded-header trust policy and proxy integration tests pending |
| Mac mini production comparison | TODO | |
| Rollback verification | TODO | |

## UI gate

The simple-komga port and new administrator UI do not begin until every backend
row required for Komga replacement is either `COMPATIBLE` or explicitly
approved as `INTENTIONAL`.
