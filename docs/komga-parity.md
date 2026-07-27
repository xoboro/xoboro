# Komga 1.25.0 backend parity ledger

This document is the release gate for Xoboro Server 1.0.

## Definition

The target is 99.9% observable backend compatibility with Komga 1.25.0. The
percentage is calculated from weighted, testable behaviors rather than source
line count. A row is complete only after implementation and automated
verification.

Status:

- `TODO`: not implemented
- `PARTIAL`: some behavior implemented, compatibility incomplete
- `COMPATIBLE`: implementation and all required tests pass
- `INTENTIONAL`: documented incompatibility approved as an Xoboro correction

## Foundation

| Capability | Status | Evidence |
|---|---|---|
| Configuration and environment overrides | PARTIAL | Strict port/database/worker/poll/lease/shutdown environment parsing and default tests; full Komga property surface pending |
| SQLite schema and migrations | PARTIAL | Flyway V1-V7 clean-install and V1-to-current upgrade tests |
| Transactions and restart recovery | PARTIAL | WAL, rollback, close/reopen durability, and filesystem scan restart tests |
| Durable prioritized task queue | PARTIAL | Priority/FIFO, deduplication, group exclusion, heartbeat, lease loss/recovery, exponential retry, dead-letter, concurrent claim, scan/analysis routing, worker-pool lifecycle, and runtime tests; metrics pending |
| Structured logs, health, readiness, metrics | PARTIAL | Worker failure logging plus `/health` liveness and SQLite-backed `/ready` contract tests; structured fields and metrics pending |
| Server-Sent Events | TODO | |
| Backup and restore | TODO | |

## Media

| Capability | Status | Evidence |
|---|---|---|
| CBZ and ZIP | PARTIAL | Real synthetic archive analysis, durable worker execution, content partitioning, persisted page/file state, stable error codes, and restart test; delivery pending |
| CBR, RAR, and RAR5 | TODO | |
| PDF | TODO | |
| EPUB 2 and EPUB 3 | TODO | |
| Content and extension detection | PARTIAL | Tika entry-content detection and corrupt/empty archive tests; outer signatures and non-ZIP formats pending |
| Natural page ordering | PARTIAL | Komga-compatible case-insensitive natural comparator fixture; RAR/PDF/EPUB pending |
| Page dimensions and media profiles | PARTIAL | Optional ImageIO dimensions, DIVINA profile, 64-bit page sizes, and persistence tests; remaining profiles pending |
| Cover and thumbnail generation | TODO | |
| Page streaming and conversion | TODO | |
| Book, series, and read-list downloads | TODO | |
| Incorrect extension repair | TODO | |
| CBR/RAR to CBZ conversion | TODO | |

## Libraries and lifecycle

| Capability | Status | Evidence |
|---|---|---|
| Library CRUD and non-overlapping roots | PARTIAL | Full settings repository, routed source validation, and canonical local/symlink overlap tests; REST pending |
| Directory exclusions and media filters | PARTIAL | Atomic exclusion replacement, media-setting round trip, and pre-descent local subtree pruning tests |
| Startup, periodic, manual, and deep scan | PARTIAL | Stable normal/deep scan tasks, strict payload handling, deleted-library no-op, startup emission, fixed-rate scheduling/rescheduling, bounded local inventory/reconciliation, and real-runtime execution tests; REST trigger and library CRUD wiring pending |
| Incremental add/change/move/delete detection | PARTIAL | Set-based initial/idempotent/change/move/restore/delete, ambiguous identity, partial-inventory, real-filesystem, and restart tests; non-local sources pending |
| Unavailable storage handling | TODO | |
| Trash, restore, and empty trash | TODO | |
| File, page, and KOReader hashes | TODO | |
| Book and series lifecycle | TODO | |
| File import, upgrade, and deletion | TODO | |
| Duplicate file detection | TODO | |
| Duplicate page detection and removal | TODO | |

## Metadata

| Capability | Status | Evidence |
|---|---|---|
| ComicInfo.xml import | TODO | |
| EPUB metadata import | TODO | |
| Local artwork | TODO | |
| Mylar metadata | TODO | |
| Series sidecars, including series.json | TODO | |
| One-shot detection | PARTIAL | Configured-directory, root-series, and candidate derivation tests; metadata aggregation pending |
| ISBN barcode detection | TODO | |
| Series aggregation | PARTIAL | Transactional path grouping, book counts, restore/delete, and partial-scan safety tests; metadata aggregation pending |
| Field locks and manual patches | TODO | |
| Bulk metadata updates | TODO | |
| Multiple thumbnails and selection | TODO | |

## Catalog and organization

| Capability | Status | Evidence |
|---|---|---|
| Series, books, and one-shots | PARTIAL | Upgrade-safe complete scan-state entities and transactional repository tests; lifecycle/metadata pending |
| Full-text search | TODO | |
| Filtering, sorting, and alphabetical groups | TODO | |
| Latest, new, updated, on-deck, keep-reading | TODO | |
| Collections and manual ordering | TODO | |
| Read lists and manual ordering | TODO | |
| ComicRack CBL import | TODO | |
| Referential authors, genres, tags, languages, publishers | TODO | |

## Users and security

| Capability | Status | Evidence |
|---|---|---|
| Initial administrator claim | PARTIAL | Anonymous GET status and header-based POST claim contracts, atomic single-winner persistence, all-role DTO, TSID identity, BCrypt hashing, null omission, validation, and real-runtime restart tests; automated initial-user configuration pending |
| Multi-user CRUD and password reset | PARTIAL | Case-insensitive identity, default roles, create/authenticate lifecycle, and atomic relation persistence tests; REST CRUD and password reset pending |
| Roles and authorization | PARTIAL | Komga role model and durable assignment tests; HTTP authorization pending |
| Library restrictions | PARTIAL | Admin, all-library, and selected-library access semantics plus persistence tests; query enforcement pending |
| Age and sharing-label restrictions | PARTIAL | Komga-compatible normalization, allow/exclude precedence, evaluation, and persistence tests; catalog query enforcement pending |
| Sessions and remember-me | TODO | |
| Basic authentication | TODO | |
| API keys | TODO | |
| OAuth2 providers | TODO | |
| Authentication activity | TODO | |
| Per-user client settings | TODO | |
| Announcements | TODO | |

## Reading

| Capability | Status | Evidence |
|---|---|---|
| Page-based progress | TODO | |
| R2 locator and EPUB progression | TODO | |
| Mark read and unread by book or series | TODO | |
| Previous and next book semantics | TODO | |
| Sync points and conflict behavior | TODO | |

## Interfaces and protocols

| Capability | Status | Evidence |
|---|---|---|
| Komga REST API v1/v2 | PARTIAL | `/api/v1/claim` GET/POST compatibility tests and production runtime wiring; remaining controllers pending |
| Komga authentication and session semantics | TODO | |
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
| Reverse-proxy and base-path support | TODO | |
| Mac mini production comparison | TODO | |
| Rollback verification | TODO | |

## UI gate

The simple-komga port and new administrator UI do not begin until every backend
row required for Komga replacement is either `COMPATIBLE` or explicitly
approved as `INTENTIONAL`.
