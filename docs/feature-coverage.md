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
| Xoboro-native HTTP API | PARTIAL | Setup/session, user administration, API-key self-service, read-only library/series/media-item discovery, page/resource discovery and delivery, file download, artwork, library administration, metadata editing, facets, collection and read-list administration, server/client settings, authentication activity, history, operational metrics, backup lifecycle, and catalog (media-item/series) maintenance commands are implemented; add recovery/unavailable-storage state endpoints, events, cursor pagination, and OpenAPI |
| Reader web UI | MISSING | Port the simple-komga browsing and comic/EPUB/PDF readers after the native API contract is stable |
| Administrator web UI | MISSING | Build library, task, user, security, metadata, duplicate, import, settings, metrics, history, and maintenance screens |
| Authentication hardening | PARTIAL | Complete the authorization review and scoped API-key policy; native cookies have same-origin CSRF protection, login is throttled, sessions are digest-only and restart-safe, and passwords use Argon2id with verified BCrypt upgrades |
| Local-library end-to-end acceptance | PARTIAL | Run large synthetic and disposable real-tree scans through native APIs, verify restart/resume, deletion safety, and reader delivery without relying on Komga-shaped routes |

## Server foundation

| Capability | Status | Remaining work |
|---|---|---|
| SQLite schema, migrations, transactions, and restart recovery | READY | Continue migration tests with every schema change |
| Durable prioritized task processing | READY | Add operation-specific cancellation only if a native workflow requires it |
| Health, readiness, structured logs, and bounded metrics | READY | Native `GET /api/xoboro/v1/metrics` JSON snapshot is implemented alongside the token-gated Prometheus scrape; remaining work is the administrator UI |
| Backup and offline restore | READY | Native backup create/list/delete over HTTP is implemented; restore stays CLI-only because it requires taking the live database offline; remaining work is the administrator UI and operator documentation |
| Server-sent updates | PARTIAL | Define native event names/resume tokens and verify reconnect behavior |
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
| Incorrect-extension repair | PARTIAL | Add PDF/EPUB repair; local ZIP/RAR repair is implemented |
| RAR-to-CBZ conversion | READY | Native API/UI |

## Formats and delivery

| Capability | Status | Remaining work |
|---|---|---|
| CBZ/ZIP analysis, pages, original download, and thumbnails | READY | Encrypted archives should return a stable native error |
| CBR/RAR/RAR5 analysis and pages | PARTIAL | Encrypted and multipart archives |
| PDF analysis, rendering, pages, and download | PARTIAL | Encrypted-document policy and remaining metadata extraction |
| EPUB 2/3, fixed layout, DiViNa, resources, and positions | PARTIAL | Complete metadata vocabulary and reader acceptance |
| Natural ordering, dimensions, content detection, and hashes | READY | Add only formats required by new sources |
| Book, series, and read-list downloads | READY | Native API/UI |
| Artwork upload, discovery, selection, generation, and caching | PARTIAL | Bundled WebP sidecars and generated collection/read-list artwork |

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
| Roles, library grants, age ratings, and sharing-label restrictions | PARTIAL | Audit enforcement across every native and protocol interface |
| API keys | PARTIAL | Native self-service management API is implemented; add scoped keys, expiration, and protocol-specific transport review |
| OAuth2/OIDC | PARTIAL | Native configuration, production-provider acceptance, and account-linking policy |
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
