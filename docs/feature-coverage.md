# Xoboro feature coverage

This is the release gate for replacing a Komga installation with Xoboro. It
tracks user-visible capabilities, not Komga endpoint or implementation parity.

Statuses:

- `READY`: usable through a supported interface and covered by automated tests.
- `PARTIAL`: the core works, but a material user workflow or hardening item is
  missing.
- `MISSING`: no complete production implementation exists.
- `FUTURE`: an Xoboro capability beyond the Komga replacement target.

## Release-critical gaps

| Capability | Status | Remaining work |
|---|---|---|
| Xoboro-native HTTP API | PARTIAL | Setup/session, user administration, API-key self-service, read-only library/series/media-item discovery, page/resource discovery and delivery, file download, artwork, library administration, metadata editing, facets, collection and read-list administration, server/client settings, authentication activity, history, operational metrics, backup lifecycle, catalog (media-item/series) maintenance commands, storage availability re-checks with the outage timestamp, trashed-entry listings, and the native event stream are implemented; OpenAPI description served and drift-tested; cursor pagination measured and declined (see docs/performance.md) |
| Reader web UI | MISSING | Port the simple-komga browsing and comic/EPUB/PDF readers after the native API contract is stable |
| Administrator web UI | MISSING | Build library, task, user, security, metadata, duplicate, import, settings, metrics, history, and maintenance screens |
| Authentication hardening | PARTIAL | The authorization audit (ADR 0090) and the scoped API-key policy (ADR 0092) are done; native cookies have same-origin CSRF protection, login is throttled, sessions are digest-only and restart-safe, and passwords use Argon2id with verified BCrypt upgrades. Remaining: native OIDC configuration and account-linking policy |
| Local-library end-to-end acceptance | PARTIAL | Run large synthetic and disposable real-tree scans through native APIs, verify restart/resume, deletion safety, and reader delivery without relying on Komga-shaped routes |

## Server foundation

| Capability | Status | Remaining work |
|---|---|---|
| SQLite schema, migrations, transactions, and restart recovery | READY | Continue migration tests with every schema change |
| Durable prioritized task processing | READY | Add operation-specific cancellation only if a native workflow requires it |
| Health, readiness, structured logs, and bounded metrics | READY | Native `GET /api/xoboro/v1/metrics` JSON snapshot is implemented alongside the token-gated Prometheus scrape; remaining work is the administrator UI |
| Backup and offline restore | READY | Native backup create/list/delete over HTTP is implemented; restore stays CLI-only because it requires taking the live database offline; remaining work is the administrator UI and operator documentation |
| Server-sent updates | READY | Native event names, resume tokens, and reconnect behavior are implemented and tested (see `docs/api/native-v1.md`); the Komga-compatible SSE stream keeps its documented broadcast gap for `OrganizationEvent`/`ArtworkEvent` per ADR 0087 |
| Runtime configuration | PARTIAL | The typed native server-settings API is implemented; document restart-required fields and add the administrator UI |

## Library and media lifecycle

| Capability | Status | Remaining work |
|---|---|---|
| Local library CRUD and root safety | READY | Native read discovery is implemented; add native administration commands and UI |
| Startup, scheduled, manual, deep, and incremental scans | READY | Large-library acceptance and native progress reporting |
| Unavailable-storage deletion protection | READY | Native status and recovery workflow |
| Add, change, move, delete, trash, and empty-trash reconciliation | READY | Native API/UI; no artificial restore API requirement |
| File import, upgrade, deletion, and transient preview | READY | Native API/UI |
| Duplicate file detection | READY | Native API/UI |
| Duplicate-page detection and removal | PARTIAL | Native API/UI and optional automatic policy execution |
| Incorrect-extension repair | READY | ZIP, RAR, PDF, and EPUB are identified by content and repaired, including across media kinds; a rename into a kind the library does not scan is declined so the repair cannot delete the book |
| RAR-to-CBZ conversion | READY | Native API/UI |

## Formats and delivery

| Capability | Status | Remaining work |
|---|---|---|
| CBZ/ZIP analysis, pages, original download, and thumbnails | READY | Encrypted archives report `UNSUPPORTED` with `ERR_1101`, read from the central directory rather than from a JDK message (ADR 0089) |
| CBR/RAR/RAR5 analysis and pages | PARTIAL | Encrypted archives report `UNSUPPORTED` with `ERR_1101`; multipart sets map a missing volume to `ERR_1102`, but that path is unverified against a real volume set because no dependency or assumed tool can write one, and suppressing trailing volumes at inventory is still missing |
| PDF analysis, rendering, pages, and download | PARTIAL | Encryption policy is settled (user-password documents are `UNSUPPORTED`, owner-password-only documents are served; ADR 0089); the document information dictionary is imported under the `importPdfBook` library setting; XMP metadata is still unread |
| EPUB 2/3, fixed layout, DiViNa, resources, and positions | PARTIAL | DRM-protected publications no longer index as `READY` (ADR 0089); metadata reads the spine reading direction, `opf:event`-tagged dates, `file-as` sort forms, and a broadened MARC relator map; `inker` and `letterer` have no MARC relator and still arrive only from ComicInfo; reader acceptance remains |
| Natural ordering, dimensions, content detection, and hashes | READY | Add only formats required by new sources |
| Book, series, and read-list downloads | READY | Native API/UI |
| Artwork upload, discovery, selection, generation, and caching | PARTIAL | Collections and read lists derive a cover from their first member with one, swept by the thumbnail-regeneration command; a tiled composite of several member covers and WebP output from the generator are not implemented, and generation is not re-triggered when membership changes |

## Metadata and catalog

| Capability | Status | Remaining work |
|---|---|---|
| ComicInfo.xml import | READY | Native metadata diagnostics |
| EPUB package metadata | PARTIAL | Remaining creator/refinement vocabulary |
| `series.json`/Mylar metadata | PARTIAL | Complete supported schema and validation diagnostics |
| One-shot detection and aggregation | READY | Native configuration and diagnostics |
| ISBN barcode detection | READY | Native configuration and diagnostics |
| Manual metadata, field locks, and bulk updates | READY | Native UI |
| Full-text search | READY | Native query contract |
| Filters, sorts, facets, latest/new/updated/on-deck/keep-reading | PARTIAL | Native core search/filter/sort/on-deck/keep-reading queries and facets are implemented; add named discovery feeds and uncommon combination/error cases |
| Collections and manual ordering | PARTIAL | Native UI and generated artwork |
| Read lists, ComicRack CBL import, and ordering | READY | Native UI |
| Historical activity | PARTIAL | Native administrator history paging is implemented; emit all native lifecycle/security/maintenance events and add retention controls |

## Users and reading

| Capability | Status | Remaining work |
|---|---|---|
| Initial administrator and multi-user management | PARTIAL | Native setup and native user management are implemented; add an automated initial-user option |
| Roles, library grants, age ratings, and sharing-label restrictions | READY | Audited across every native route group and protocol adapter (ADR 0090); roles are checked at the route, library grants and content restrictions are SQL predicates carried by the `CatalogAccess` that every `CatalogReadRepository` method requires. The audit fixed a KOReader fingerprint path that enforced library grants but not content restrictions, and collapsed five duplicate copies of the access projection into one |
| API keys | READY | Native self-service management, role-subset scopes and absolute expiry (ADR 0092); the scope is applied by narrowing the authenticated caller, so it holds on the native API and every protocol adapter without per-route checks. The Komga-compatible surface creates unscoped, non-expiring keys because its contract has no such field (ADR 0082) |
| OAuth2/OIDC | PARTIAL | Account-linking policy is explicit with a safe default, and administrators can read back the resolved providers and policy over the native API (ADR 0093). Provider registration stays in environment variables so client secrets never enter the database; write access is declined, not deferred. Production-provider acceptance against a live provider remains open because it needs real credentials |
| Authentication activity | PARTIAL | Native administrator and caller-scoped activity paging is implemented; record session/OAuth flows and schedule retention cleanup |
| Per-user client settings | READY | Native effective, per-user write, and administrator global-settings APIs are implemented; replace arbitrary compatibility keys with versioned native preferences where possible |
| Page and Readium locator progress | PARTIAL | Reader acceptance of the mutation/conflict contract |
| Mark read/unread, keep reading, and previous/next navigation | PARTIAL | Native API/UI plus end-to-end multi-item navigation tests |

## Interoperability and migration

| Capability | Status | Remaining work |
|---|---|---|
| OPDS v1 | READY | Keep protocol regression tests |
| OPDS v2 | PARTIAL | Improve search relevance and native authentication presentation |
| Kobo Sync and KEPUB | PARTIAL | Kobo Store proxy merging and device acceptance tests |
| KOReader Sync | READY | Device acceptance tests |
| One-way Komga 1.25 database import | PARTIAL | Finish production acceptance for restrictions, progress, collections, and read lists; never require runtime DB compatibility |
| Docker, Compose, reverse proxy, and base path | READY | Native UI assets and release packaging |

## Xoboro extensions

These are valuable but do not count against Komga feature coverage.

| Capability | Status | Remaining work |
|---|---|---|
| WebDAV and other remote source adapters | FUTURE | Source read/list/cache/mutation contracts and credential storage |
| Video and audio timelines | FUTURE | Native scanning, metadata, progress, transcoding/delivery, and clients |
| Private Android and iOS applications | FUTURE | Local libraries, remote Xoboro/NAS connections, offline holdings, and native readers |
