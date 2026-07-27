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
| Configuration and environment overrides | TODO | |
| SQLite schema and migrations | PARTIAL | Flyway V1-V2 clean-install and upgrade tests |
| Transactions and restart recovery | PARTIAL | WAL, rollback, close/reopen durability tests |
| Durable prioritized task queue | PARTIAL | Priority/FIFO, deduplication, group exclusion, lease recovery, retry, dead-letter, and concurrent-claim tests |
| Structured logs, health, readiness, metrics | PARTIAL | `/health` contract test |
| Server-Sent Events | TODO | |
| Backup and restore | TODO | |

## Media

| Capability | Status | Evidence |
|---|---|---|
| CBZ and ZIP | TODO | |
| CBR, RAR, and RAR5 | TODO | |
| PDF | TODO | |
| EPUB 2 and EPUB 3 | TODO | |
| Content and extension detection | TODO | |
| Natural page ordering | TODO | |
| Page dimensions and media profiles | TODO | |
| Cover and thumbnail generation | TODO | |
| Page streaming and conversion | TODO | |
| Book, series, and read-list downloads | TODO | |
| Incorrect extension repair | TODO | |
| CBR/RAR to CBZ conversion | TODO | |

## Libraries and lifecycle

| Capability | Status | Evidence |
|---|---|---|
| Library CRUD and non-overlapping roots | PARTIAL | Full settings repository, routed source validation, and canonical local/symlink overlap tests; REST pending |
| Directory exclusions and media filters | PARTIAL | Atomic exclusion replacement and media-setting round-trip tests |
| Startup, periodic, manual, and deep scan | PARTIAL | Lifecycle queue ports and scan-trigger compatibility tests; durable worker pending |
| Incremental add/change/move/delete detection | TODO | |
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
| One-shot detection | TODO | |
| ISBN barcode detection | TODO | |
| Series aggregation | TODO | |
| Field locks and manual patches | TODO | |
| Bulk metadata updates | TODO | |
| Multiple thumbnails and selection | TODO | |

## Catalog and organization

| Capability | Status | Evidence |
|---|---|---|
| Series, books, and one-shots | TODO | |
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
| Initial administrator claim | TODO | |
| Multi-user CRUD and password reset | TODO | |
| Roles and authorization | TODO | |
| Library restrictions | TODO | |
| Age and sharing-label restrictions | TODO | |
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
| Komga REST API v1/v2 | TODO | |
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
