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

The non-OpenAPI inventory contains 70 operations, including the two OAuth2
browser-flow routes.

Status:

- `TODO`: not implemented
- `PARTIAL`: some behavior implemented, compatibility incomplete
- `COMPATIBLE`: implementation and all required tests pass
- `INTENTIONAL`: documented incompatibility approved as an Xoboro correction

## Foundation

| Capability | Status | Evidence |
|---|---|---|
| Configuration and environment overrides | PARTIAL | Komga-compatible administrator settings GET/PATCH, durable defaults and nullable overrides, explicit null wire fields, environment/database/effective source reporting, restart-applied port and context path, trusted proxy and protected metrics configuration, live worker resizing, live remember-me duration and key rotation, strict parsing, and validation tests; full Komga property surface pending |
| SQLite schema and migrations | PARTIAL | Flyway V1-V24 clean-install and V1-to-current upgrade tests |
| Extensible media domain | PARTIAL | Portable `Library` → `MediaItem` sealed hierarchy for Comic, Novel, Book, Video, and Audio; capability-based page/text/timeline/content behavior; canonical table/ID/read repository; same-transaction Komga Book mirroring; independent Series-free Video/Audio persistence and migration/restart tests; native timeline scanning, metadata, progress, and delivery pending |
| Transactions and restart recovery | PARTIAL | WAL, rollback, close/reopen durability, and filesystem scan restart tests |
| Durable prioritized task queue | COMPLETE | Priority/FIFO, deduplication, group exclusion, heartbeat, lease loss/recovery, exponential retry, dead-letter, concurrent claim, scan/analysis routing, active-lease-safe administrator queue clearing, dynamically resizable worker-pool lifecycle, bounded queue/worker gauges, and runtime tests |
| Structured logs, health, readiness, metrics | COMPLETE | Single-line JSON events and query-free access logs, worker exception logging, `/health` liveness, SQLite-backed `/ready`, and token-protected bounded Prometheus request/readiness/queue/worker/uptime metrics with no path or identifier labels |
| Server-Sent Events | PARTIAL | Authenticated bounded per-user streams, role/user filtering, 15-second heartbeat comments, 10-second administrator task snapshots, graceful runtime shutdown, and post-commit Library/Series/Book/import/organization/artwork/read-progress/session publication through one exact-name bridge with producer and contract tests; reconnect and differential delivery behavior pending |
| Backup and restore | COMPLETE | Live-WAL `VACUUM INTO` snapshots, integrity validation before publish and restore, file flush, same-directory atomic replacement with documented fallback, corrupt-input preservation, stale WAL/SHM cleanup, running-server exclusion, explicit overwrite consent, offline CLI commands, and restart tests |

## Media

| Capability | Status | Evidence |
|---|---|---|
| CBZ and ZIP | PARTIAL | Real synthetic archive analysis, durable worker execution, content partitioning, persisted page/file state, exact-entry page streaming, original-file streaming, stable error codes, and restart tests; differential behavior pending |
| CBR, RAR, and RAR5 | PARTIAL | Signature-routed in-process RAR3-through-RAR7 analysis, bounded dictionary, natural page indexing, dimensions, leading/trailing hashes, indexed page streaming and conversion, RAR4/RAR5 synthetic fixtures, and source lifetime tests; encrypted/multipart and differential fixtures pending |
| PDF | PARTIAL | PDFBox page analysis, optional crop-box dimensions, bounded page indexing, JPEG rendering, raw single-page PDF extraction, WebPub PDF manifest, source materialization cleanup, and synthetic document tests; encrypted documents, metadata, and differential fixtures pending |
| EPUB 2 and EPUB 3 | PARTIAL | Secure ZIP/container/package parsing, manifest/spine resources, fixed-layout and DiViNa detection, EPUB3 navigation plus EPUB2 NCX fallback, Readium positions, KEPUB span detection, package metadata import, persisted extensions, resource streaming with CSP, WebPub manifest, bounded KEPUB conversion, and synthetic fixed/reflowable tests; remaining metadata vocabulary and differential fixtures pending |
| Content and extension detection | PARTIAL | Bounded ZIP/RAR4/RAR5 outer-signature routing plus Tika entry-content detection and corrupt/empty archive tests; PDF/EPUB outer mismatch repair and differential fixtures pending |
| Natural page ordering | PARTIAL | Komga-compatible case-insensitive natural comparator fixtures for ZIP and RAR; PDF/EPUB differential behavior pending |
| Page dimensions and media profiles | PARTIAL | Optional ImageIO dimensions, DIVINA profile, 64-bit page sizes, and persistence tests; remaining profiles pending |
| Cover and thumbnail generation | PARTIAL | Durable owner-scoped Book/Series/Collection/ReadList artwork, bounded multipart upload, decoded-pixel safety, JPEG normalization/resizing, atomic selection, uploaded-only deletion, authenticated byte delivery, first-visible-book page fallback, low-priority bulk Book poster generation, and atomic local Book/Series sidecar replacement with user-selection preservation; differential caching pending |
| Page streaming and conversion | PARTIAL | Exact analyzed-entry streaming, EPUB resource streaming, PDF JPEG rendering and raw single-page extraction, raw and zero-based routes, JPEG/PNG conversion, bounded page thumbnails, role and catalog-access enforcement, CSP, and resource-lifetime tests; PDF Accept negotiation and differential caching semantics pending |
| Book, series, and read-list downloads | PARTIAL | Original book download streams source materialization with attachment naming and single-byte-range responses; Series and ReadList ZIPs stream entries without whole-archive buffering with role/access enforcement and collision-safe names; differential archive metadata pending |
| Incorrect extension repair | PARTIAL | Durable per-media-item signature-based ZIP/RAR extension repair, sibling atomic rename, stable catalog identity, source-fact update, post-repair analysis/scan, and local integration tests; PDF/EPUB and non-local mutation adapters pending |
| CBR/RAR to CBZ conversion | PARTIAL | Durable RAR-to-CBZ entry streaming with dictionary, entry-count, expanded-size, duplicate-name and traversal guards; sibling atomic replacement preserves media ID and progress, clears changed hashes, and schedules analysis/scan; non-local mutation and differential fixtures pending |

## Libraries and lifecycle

| Capability | Status | Evidence |
|---|---|---|
| Library CRUD and non-overlapping roots | PARTIAL | Komga v1 list/get/create/PATCH/deprecated PUT/delete routes, full settings DTO, selected-library visibility, administrator-only root disclosure and mutation, durable lifecycle wiring, local path validation, duplicate/overlap rejection, and contract tests; non-local source administration and differential tests pending |
| Directory exclusions and media filters | PARTIAL | Atomic exclusion replacement, media-setting round trip, and pre-descent local subtree pruning tests |
| Startup, periodic, manual, and deep scan | PARTIAL | Stable normal/deep scan tasks, strict payload handling, deleted-library no-op, startup emission, fixed-rate scheduling/rescheduling/cancellation from live CRUD, library and target-scoped analysis/metadata REST triggers, bounded metadata-only inventory, prepared-batch staging, indexed set-based reconciliation/task emission, 5,000-item regression coverage, and real-runtime execution tests; differential tests pending |
| Incremental add/change/move/delete detection | PARTIAL | Set-based initial/idempotent/change/move/restore/delete, ambiguous identity, partial-inventory, real-filesystem, restart tests, SQL-level unchanged-file suppression, and successful-analysis chaining to one Book plus one Series metadata refresh; non-local sources pending |
| Unavailable storage handling | PARTIAL | Source-level unavailable boundary aborts reconciliation before deletion, records the first outage timestamp, suppresses duplicate transitions, clears on recovery, publishes `LibraryChanged`, and has detached/re-attached local-root integration coverage; remote source adapters pending |
| Trash, restore, and empty trash | PARTIAL | Logically deleted books and now-empty deleted series are removed atomically, dependent media cascades, administrator REST requests emit deduplicated high-priority durable jobs, and `emptyTrashAfterScan` schedules cleanup after successful scans; restore API and differential tests pending |
| File, page, and KOReader hashes | PARTIAL | Komga-compatible seeded XXH3-128 whole-file hashes reuse source materialization; optional first/last-three page hashes persist during Comic analysis, JPEG pixels are normalized before hashing, and non-empty hashes are indexed; exact KOReader sparse partial-MD5 is computed during shared source materialization and indexed by canonical MediaItem ID with deterministic vectors; differential fixtures pending |
| Book and series lifecycle | PARTIAL | Administrator Book/Series file deletion emits deduplicated, source-aware durable tasks and schedules catalog reconciliation; restore and differential events pending |
| File import, upgrade, and deletion | PARTIAL | Administrator COPY/MOVE/HARDLINK imports and upgrades use canonical root containment, validated destination leaves, sibling temporary files, atomic replacement, retry-safe deletion, and durable tasks; non-local mutation adapters pending |
| Filesystem and transient import discovery | PARTIAL | Administrator filesystem listing hides dot entries and validates absolute directories; session-scoped transient CBZ/ZIP, EPUB, and PDF discovery rejects Library overlap, reuses production analysis, and streams previews; differential envelopes and remote import pending |
| Duplicate file detection | PARTIAL | Administrator Book route groups non-empty equal file hashes and sizes in SQLite with stable paging and Komga DTO projection; differential sorting pending |
| Duplicate page detection and removal | PARTIAL | Known/unknown/match queries, action updates, live bounded thumbnails, SQL aggregation/paging, restart persistence, administrator routes, highest-priority per-Book jobs, atomic local CBZ rewriting, deletion counters, and scan reconciliation are implemented; non-local mutation and automatic policy execution remain pending |

## Metadata

| Capability | Status | Evidence |
|---|---|---|
| ComicInfo.xml import | PARTIAL | Bounded, secure root-entry CBZ parsing imports book and series fields through source-aware providers; synthetic field, malformed-input, setting, persistence, durable-job, and runtime tests; complete field mapping, collections, read lists, EPUB, and differential fixtures pending |
| EPUB metadata import | PARTIAL | Secure container/package resolution imports main title, description, normalized date, refined creator/contributor roles, subjects, validated ISBN, absolute links, publisher, language, EPUB 3 series/group position and Calibre fallbacks through the ordered lock-preserving provider chain; full vocabulary and differential fixtures pending |
| Local artwork | PARTIAL | Source-aware root-contained discovery implements Komga-compatible book basename/numeric suffix and series cover/default/folder/poster/series conventions, bounded reads, decoded-pixel validation, JPEG normalization, invalid-candidate isolation, atomic multi-sidecar replacement, user-selection preservation, fallback selection, restart persistence, and integration tests; remote adapters and bundled WebP decoding pending |
| Mylar metadata | PARTIAL | Source-aware bounded `series.json` import supports textual/formatted descriptions, title, publisher, year/volume naming, status, age rating, and issue count with synthetic tests; complete schema and differential fixtures pending |
| Series sidecars, including series.json | PARTIAL | Canonical-root containment, traversal and size guards, setting-aware provider selection, durable per-library refresh, and real-runtime persistence tests; artwork sidecars and remote sources pending |
| One-shot detection | PARTIAL | Configured-directory, root-series, and candidate derivation tests; metadata aggregation pending |
| ISBN barcode detection | PARTIAL | Setting-aware EAN-13 decoding scans at most the final three pages in reverse then the first three, excludes EPUB, validates 978/979 ISBN prefixes and check digits, uses bounded source-subsampled images, isolates damaged pages, closes streams, and has generated synthetic barcode tests; differential image fixtures pending |
| Series aggregation | PARTIAL | Transactional path grouping, book counts, restore/delete, partial-scan safety, ordered metadata providers, and lock-preserving book/series merge tests; full Komga aggregation precedence pending |
| Field locks and manual patches | PARTIAL | Durable normalized lock sets, refresh-time lock preservation, presence-sensitive book/series REST patches, explicit-null clearing, administrator authorization, persistence/route tests, and post-write Book/Series invalidation events; differential validation pending |
| Bulk metadata updates | PARTIAL | Komga-compatible book-ID-to-patch map skips missing targets, updates valid metadata, and emits one Book invalidation per successful patch; differential validation pending |
| Multiple thumbnails and selection | PARTIAL | Shared artwork lifecycle persists multiple owner-scoped candidates with one selected row, restart durability, owner isolation, administrator mutation, all 24 Komga poster operations, generated/sidecar ingestion, and exact owner-specific add/delete events; differential image caching pending |

## Catalog and organization

| Capability | Status | Evidence |
|---|---|---|
| Series, books, and one-shots | PARTIAL | Upgrade-safe complete scan-state entities, metadata relations, database-paged read model, Komga DTOs, detail and series-book REST routes, and transactional/integration tests; read progress and differential tests pending |
| Full-text search | COMPLETE | Unicode/diacritic-aware FTS5 Book and Series documents cover titles, alternate titles, summaries, contributors/roles, tags, genres, publisher, language, ISBN, and links; initial migration, transactional metadata refresh, parent/child propagation, safe quoted prefix parsing, punctuation isolation, and synthetic update tests are complete |
| Filtering, sorting, and alphabetical groups | PARTIAL | Allowlisted SQL sorting, legacy library/publisher/language/genre/tag filters, stable pagination, alphabetical groups, and bounded recursive `allOf`/`anyOf` Komga conditions compile to bound SQL across membership, metadata, read/media state, artwork, date/numeric/string/null, and aggregate fields after authorization/content restrictions; differential tests pending |
| Latest, new, updated, on-deck, keep-reading | COMPLETE | Book latest/on-deck and Series latest/new/updated use explicit database sorts, access filtering, and durable per-series progress aggregates. Keep-reading is joined, filtered, recent-read sorted, counted, and paged in SQL; READY/in-progress semantics, anonymous access, OPDS projection, and pagination beyond 10,000 synthetic items are regression tested |
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
| OAuth2 providers | PARTIAL | Anonymous provider listing, Spring-compatible registration binding, authorization-code exchange, one-time state plus browser-bound CSRF correlation, verified forwarded-origin callbacks, cached issuer discovery with exact issuer validation, RSA and P-256/P-384/P-521 ID-token verification, JWK algorithm/type/use enforcement, audience/authorized-party/time/nonce validation, verified-email policy, GitHub primary verified-email fallback, account matching/creation, Komga error codes, session issuance, failure redirects, and login activity tests; differential configured-provider tests pending |
| Authentication activity | PARTIAL | Durable success/failure ledger, password and API-key source recording, credential-safe failed-key fingerprinting, User-Agent/IP capture, current-user/admin pagination and sorting, API-key-filtered latest lookup, retention cutoff, cascade cleanup, and restart tests; sessions, OAuth, demo mode, proxy IP policy, and scheduled cleanup pending |
| Per-user client settings | PARTIAL | Global and per-user namespaced string settings, anonymous visibility filtering, administrator authorization, user isolation, transactional upsert, selective deletion, cascade cleanup, restart persistence, validation, and six REST operation tests |
| Announcements | PARTIAL | Komga JSON Feed retrieval with one-hour expire-after-access cache, single-flight refresh, unknown-field tolerance, administrator authorization, per-user durable read flags, duplicate-safe marking, user cascade cleanup, empty-feed 404 and upstream-error propagation, restart persistence, and two REST operation tests |

## Reading

| Capability | Status | Evidence |
|---|---|---|
| Page-based progress | PARTIAL | Durable per-user page/completed/read-date/device rows, transactional series aggregates, page-bound validation, Book DTO projection, REST integration tests, and filtered SSE change/delete events; device mutation and differential tests pending |
| R2 locator and EPUB progression | PARTIAL | Durable per-user Readium locator/device/timestamp state, stale-write conflict rejection, authenticated GET/PUT contracts, persisted EPUB positions, position-list contract, and DiViNa/EPUB integration tests; advanced locator reconciliation and differential fixtures pending |
| Mark read and unread by book or series | PARTIAL | Book PATCH/DELETE and set-based Series POST/DELETE routes plus Mihon ReadList index and Series number-sort adapters enforce catalog access and update durable aggregates; bulk and differential adapters pending |
| Previous and next book semantics | PARTIAL | Stable metadata number, relative-path, and ID tie-breaking in SQL with access checks and boundary tests; differential and read-state semantics pending |
| Sync points and conflict behavior | PARTIAL | Durable user/API-key-scoped sync points, restart persistence, foreign-key cleanup, and authenticated complete or selected-key deletion; Kobo creation/conflict behavior pending |

## Interfaces and protocols

| Capability | Status | Evidence |
|---|---|---|
| Komga REST API v1/v2 | PARTIAL | All 165 pinned operations have an implementation in progress, including management info, font resources, release discovery, durable poster regeneration, and atomic duplicate-page removal; differential certification and exact schema/error completion pending |
| Komga authentication and session semantics | PARTIAL | Basic, `X-API-Key`, `KOMGA-SESSION`, `X-Auth-Token`, and `komga-remember-me` authentication, multi-provider principal propagation, seven-day inactivity, 365-day remember-me restoration, transport conversion, logout, credential deletion, password-signature invalidation, and security-change invalidation tests; OAuth pending |
| OpenAPI document | COMPLETE | The unmodified checksum-pinned Komga 1.25.0 OpenAPI 3.1 contract is bundled in the runtime and served anonymously at context-aware `/v3/api-docs`, with source/license provenance and route tests |
| OPDS v1 | PARTIAL | All 18 Atom/OpenSearch catalog, browse, acquisition, thumbnail, and page operations use access-filtered catalog data and streamed media with synthetic route coverage; differential XML, caching, and failure fixtures pending |
| OPDS v2 and authentication document | PARTIAL | All 30 feed, browse, search, artwork, page, profile manifest, authentication-document, and progression operations reuse shared WebPub/progress boundaries with synthetic integration coverage; exact unauthorized document and differential fixtures pending |
| Kobo Sync and KEPUB | PARTIAL | All 15 URL-token routes, role enforcement, initialization, device auth, access-filtered metadata/state/shelves, restart-safe incremental SQLite snapshots and continuation cursors, thumbnails, EPUB downloads, bounded external kepubify conversion with revision cache invalidation, and no-proxy catch-all behavior have synthetic integration coverage; Kobo Store proxy merging and differential device fixtures pending |
| KOReader Sync | PARTIAL | All four routes, `X-Auth-User` API-key authentication, role enforcement, duplicate/missing fingerprint behavior, exact partial-MD5 generation, PDF/DiViNa page progress, both EPUB locator forms, durable Readium conversion, and synthetic integration tests; reference differential fixtures pending |
| SSE event contracts | PARTIAL | Authenticated Ktor SSE stream, all Komga 1.25 event names and JSON DTOs, bounded subscribers, heartbeat, administrator task/import filtering, user-isolated progress/session delivery, and lifecycle producers for Library/Series/Book/Collection/ReadList/artwork; differential reconnect behavior pending |

## Migration and operations

| Capability | Status | Evidence |
|---|---|---|
| Read-only Komga 1.25.0 database importer | COMPLETE | Exact Flyway schema and integrity validation, SQLite read-only/query-only snapshot, empty-target guard, bounded batched copy, zero-to-one-based page conversion, atomic replacement, source immutability test, synthetic 1.25 fixture, and isolated production-snapshot comparison |
| Users and restrictions migration | PARTIAL | Password hashes, roles, library grants, sharing labels, API keys, authentication activity, user client settings, and announcement reads migrate with synthetic restart-safe verification; live authentication comparison pending |
| Metadata and locks migration | COMPLETE | Series/book fields, locks, relations, media/page/file indexes, embedded artwork, page-hash policy, history, and full-text index rebuild migrate in one transaction; external artwork is intentionally rediscovered, and source/target production-snapshot cardinalities match |
| Progress migration | PARTIAL | Per-book and per-series progress plus bounded gzip locator conversion migrate with synthetic verification; differential reader and sync-client comparison pending |
| Collections and read-list migration | PARTIAL | Collection/read-list identity, metadata, ordering, and membership migrate with synthetic verification; production snapshot comparison pending |
| Docker amd64 and arm64 | COMPLETE | Pinned Temurin 26 multi-stage, non-root, health-checked image and verified GHCR buildx publication with amd64/arm64 manifest, SBOM, and provenance |
| Compose deployment | COMPLETE | Plain `docker compose up -d`, persistent configuration volume, read-only media bind, read-only root filesystem, bounded executable tmpfs, dropped capabilities, graceful shutdown, context-aware health check, CI smoke test, and isolated deployment drill |
| Reverse-proxy and base-path support | COMPLETE | Restart-applied database- or environment-configured base path, fail-closed physical-peer allowlist for RFC and X-forwarded headers, known-proxy removal, verified-origin authentication/cookies/absolute links/OAuth callbacks, and spoofing/origin integration tests |
| Mac mini production comparison | COMPLETE | A read-only, consistency-checked Komga snapshot imported into an isolated volume; source/target cardinalities, page-number transformation, foreign keys, and both database integrity checks passed without reading catalog names |
| Rollback verification | COMPLETE | The disposable target volume, image, and snapshot were removed after the comparison; the live Komga database and media were never mounted writable, and the existing service remained healthy before and after the drill |

## UI gate

The simple-komga port and new administrator UI do not begin until every backend
row required for Komga replacement is either `COMPATIBLE` or explicitly
approved as `INTENTIONAL`.
