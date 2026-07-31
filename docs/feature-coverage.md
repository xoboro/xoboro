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
| Reader web UI | PARTIAL | Built on the native API and served by the server: home shelves from the five named feeds, series screens in reading order, and three readers — the comic reader (scroll/paged/split modes, single-flight priority loader, aspect-ratio reservation, direction-aware spread splitting), an EPUB reader following spine-derived positions with locator progress and sandboxed chapter markup, and PDF over server-rendered pages. Progress carries a strictly increasing client clock and a `409 stale_progress` prompts with the other device's position instead of resolving silently. Remaining: acceptance against a real library in a browser rather than mocked responses, offline reading (deliberately out of scope), and the reader-side search and filter surfaces |
| Administrator web UI | PARTIAL | Eleven screens are built and served: Overview, Libraries, Trash, Tasks, Users, API keys, Security, Duplicates, History, Backups and Settings, in a lazily-loaded chunk a reader never downloads. The destructive rules from `docs/DESIGN.md` are enforced and tested — server-supplied blast-radius counts, exact typed confirmation, `force=true` as a separate decision behind an availability re-check, and the two task-discard routes kept apart. Screens state what the server will not do rather than offering it: duplicate-page decisions are recorded and removal is not performed, and the backups screen names the CLI restore command. Remaining: metadata editing and collection/read-list administration, and acceptance against a live deployment rather than mocked responses |
| Authentication hardening | READY | Every named item is done: the authorization audit (ADR 0090), scoped and expiring API keys (ADR 0092), and the explicit OIDC account-linking policy with a safe default (ADR 0093). Native cookies have same-origin CSRF protection, login is throttled, sessions are digest-only and restart-safe, passwords use Argon2id with verified BCrypt upgrades, and every native session outcome is recorded as authentication activity. Acceptance against a live OIDC provider needs real credentials and is tracked in the OAuth2/OIDC row |
| Local-library end-to-end acceptance | PARTIAL | A real CBZ tree is registered, scanned, analyzed, navigated, read and progressed entirely through native routes, then survives a runtime restart with identifiers, reading order and progress intact; a vanished file leaves the live listing but stays recoverable under `trashed=true` (ADR 0101). Remaining: the same walk at a size where it takes minutes rather than seconds, and against a disposable real-world tree rather than a generated one |

## Server foundation

| Capability | Status | Remaining work |
|---|---|---|
| SQLite schema, migrations, transactions, and restart recovery | READY | Continue migration tests with every schema change |
| Durable prioritized task processing | READY | Add operation-specific cancellation only if a native workflow requires it |
| Health, readiness, structured logs, and bounded metrics | READY | Native `GET /api/xoboro/v1/metrics` JSON snapshot is implemented alongside the token-gated Prometheus scrape; remaining work is the administrator UI |
| Backup and offline restore | READY | Native backup create/list/delete over HTTP is implemented; restore stays CLI-only because it requires taking the live database offline; remaining work is the administrator UI and operator documentation |
| Server-sent updates | READY | Native event names, resume tokens, and reconnect behavior are implemented and tested (see `docs/api/native-v1.md`); the Komga-compatible SSE stream keeps its documented broadcast gap for `OrganizationEvent`/`ArtworkEvent` per ADR 0087 |
| Runtime configuration | PARTIAL | The typed native server-settings API is implemented, and which fields need a restart is documented per setting — the API reports it by `databaseSource` differing from `effectiveValue` rather than by a derived flag that could disagree. Remaining: the administrator UI |

## Library and media lifecycle

| Capability | Status | Remaining work |
|---|---|---|
| Local library CRUD and root safety | READY | Native read discovery is implemented; add native administration commands and UI |
| Startup, scheduled, manual, deep, and incremental scans | READY | Large-library acceptance and native progress reporting |
| Unavailable-storage deletion protection | READY | Native status and recovery workflow |
| Add, change, move, delete, trash, and empty-trash reconciliation | READY | Native API/UI; no artificial restore API requirement |
| File import, upgrade, deletion, and transient preview | READY | Native API/UI |
| Duplicate file detection | READY | Native API/UI |
| Duplicate-page detection and removal | PARTIAL | Detection and a native administrator API for reviewing candidates, listing the media items that carry a hash, and recording a decision are implemented (ADR 0100). `IGNORE` takes effect; **removal itself is deliberately unimplemented** because it rewrites an archive on disk, so `DELETE_AUTO`/`DELETE_MANUAL` are stored as stated intent and `deleteCount` is always 0. Remaining: the removal executor, an automatic policy that runs it, and the administrator UI |
| Incorrect-extension repair | READY | ZIP, RAR, PDF, and EPUB are identified by content and repaired, including across media kinds; a rename into a kind the library does not scan is declined so the repair cannot delete the book |
| RAR-to-CBZ conversion | READY | Native API/UI |

## Formats and delivery

| Capability | Status | Remaining work |
|---|---|---|
| CBZ/ZIP analysis, pages, original download, and thumbnails | READY | Encrypted archives report `UNSUPPORTED` with `ERR_1101`, read from the central directory rather than from a JDK message (ADR 0089) |
| CBR/RAR/RAR5 analysis and pages | PARTIAL | Encrypted archives report `UNSUPPORTED` with `ERR_1101`; multipart sets map a missing volume to `ERR_1102`, though that path stays unverified against a real volume set because no dependency or assumed tool can write one. Continuation volumes of a `.partN.rar` set are suppressed at scan when their first volume is present beside them (ADR 0096), so one set yields one media item |
| PDF analysis, rendering, pages, and download | READY | Encryption policy is settled (user-password documents are `UNSUPPORTED`, owner-password-only documents are served; ADR 0089); both the document information dictionary and the XMP packet are imported under the `importPdfBook` library setting, with per-field precedence (ADR 0097) — XMP wins for authors and tags because it carries them as structured lists, the dictionary wins elsewhere and XMP fills gaps |
| EPUB 2/3, fixed layout, DiViNa, resources, and positions | PARTIAL | Spine-derived positions are now exposed natively at `GET /media-items/{id}/positions`, which is what makes a correct EPUB reader possible: the resource manifest is in OPF order, so a client following it presents chapters in whatever sequence the packager wrote. DRM-protected publications no longer index as `READY` (ADR 0089); metadata reads the spine reading direction, `opf:event`-tagged dates, `file-as` sort forms, 39 MARC relator codes, and their English display names for producers that write `role="Illustrator"` instead of `role="ill"`; `inker` and `letterer` have no MARC relator and arrive only from ComicInfo, and `ltr` stays deliberately unmapped because it looks like "letterer" but is not it. Reader acceptance remains |
| Natural ordering, dimensions, content detection, and hashes | READY | Add only formats required by new sources |
| Book, series, and read-list downloads | READY | Native API/UI |
| Artwork upload, discovery, selection, generation, and caching | READY | Collections and read lists derive a cover from their members: a 2×2 mosaic when four member covers are available, one member's cover otherwise, and the sweep re-derives a cover once the grouping's `updatedAtMillis` passes it, so a membership change is picked up without wiring the three mutation paths (ADR 0098). WebP is settled (ADR 0104): reading is supported through a pure-Java `ImageIO` reader, which fixed a real defect — the scanner offered to discover `cover.webp` while the decoder had no reader for it, so an ordinary WebP cover became a permanently failing artwork task. WebP **output** is declined, because every library that writes it binds to a native `libwebp` and the image is multi-architecture; a test asserts no writer is registered so a dependency bump cannot enable it silently |

## Metadata and catalog

| Capability | Status | Remaining work |
|---|---|---|
| ComicInfo.xml import | READY | Native metadata diagnostics |
| EPUB package metadata | READY | Creator and contributor credits resolve through one path whether the role is a relator code, an English relator name, an already-canonical vocabulary name, or undeclared; an unresolvable role reaches the catalog verbatim rather than being dropped |
| `series.json`/Mylar metadata | READY | Reads `name`, `volume`/`year`, `status`, both description forms, `publisher`, `age_rating`, `total_issues`, `booktype` and `imprint`; `comicid`, `collects`, `publication_run`, `comic_image` and `type` are documented as deliberately unread with a reason each. A malformed, non-Mylar, nameless, or drifted file is reported as a diagnostic instead of behaving like a directory with no sidecar (ADR 0099) |
| One-shot detection and aggregation | READY | Native configuration and diagnostics |
| ISBN barcode detection | READY | Native configuration and diagnostics |
| Manual metadata, field locks, and bulk updates | READY | Native UI |
| Full-text search | READY | Native query contract |
| Filters, sorts, facets, latest/new/updated/on-deck/keep-reading | READY | Native search, filter, sort, on-deck and keep-reading queries, facets, and five named discovery feeds whose ordering is fixed server-side so clients cannot disagree about what "latest" means (ADR 0103). A feed rejects a `sort` override rather than ignoring it |
| Collections and manual ordering | PARTIAL | Native UI and generated artwork |
| Read lists, ComicRack CBL import, and ordering | READY | Native UI |
| Historical activity | READY | Native administrator history paging and a configurable retention window with a six-hourly sweep (ADR 0095); `poster.changed` covers all four artwork owner kinds (ADR 0094), and native session outcomes are recorded as authentication activity |

## Users and reading

| Capability | Status | Remaining work |
|---|---|---|
| Initial administrator and multi-user management | READY | Native setup, native user management, and startup provisioning from `XOBORO_INITIAL_ADMIN_EMAIL` plus a password file (preferred) or environment variable (ADR 0102). The claim runs only while the server is unclaimed, so a restart cannot reset the password into a back door, and half-configuration fails startup rather than coming up unclaimed |
| Roles, library grants, age ratings, and sharing-label restrictions | READY | Audited across every native route group and protocol adapter (ADR 0090); roles are checked at the route, library grants and content restrictions are SQL predicates carried by the `CatalogAccess` that every `CatalogReadRepository` method requires. The audit fixed a KOReader fingerprint path that enforced library grants but not content restrictions, and collapsed five duplicate copies of the access projection into one |
| API keys | READY | Native self-service management, role-subset scopes and absolute expiry (ADR 0092); the scope is applied by narrowing the authenticated caller, so it holds on the native API and every protocol adapter without per-route checks. The Komga-compatible surface creates unscoped, non-expiring keys because its contract has no such field (ADR 0082) |
| OAuth2/OIDC | PARTIAL | Account-linking policy is explicit with a safe default, and administrators can read back the resolved providers and policy over the native API (ADR 0093). Provider registration stays in environment variables so client secrets never enter the database; write access is declined, not deferred. Production-provider acceptance against a live provider remains open because it needs real credentials |
| Authentication activity | READY | Native administrator and caller-scoped paging, a configurable retention window with a scheduled sweep (ADR 0095), and recording of every native session outcome - setup, accepted login, rejected credentials, and cross-site rejection - alongside the Komga-compatible password, API-key, remember-me and OAuth2 sources |
| Per-user client settings | READY | Native effective, per-user write, and administrator global-settings APIs are implemented; replace arbitrary compatibility keys with versioned native preferences where possible |
| Page and Readium locator progress | READY | The mutation/conflict contract is exercised end to end: a newer write applies, an older one is refused with `409 stale_progress` rather than silently rewinding the reader's place, and progress survives a restart (ADR 0101). One known deviation, deliberately not corrected: `totalProgression` is `position / count`, so it reports the progress at the *end* of a position and is one position ahead of the Readium convention. Kobo's `ProgressPercent` is derived from it and is correspondingly high, and KOReader turns it back into a stored page through `pageFor`, so the error is durable rather than presentational — bounded at roughly one page. ADR 0105 records why the fix is a task of its own: `pageFor` inverts the current convention, so correcting the analyzer alone would stop a KOReader user ever reaching a book's last page |
| Mark read/unread, keep reading, and previous/next navigation | PARTIAL | Multi-item navigation is covered end to end against a scanned tree: previous/next walk the in-series reading order from a middle item, and the ends return `404` rather than wrapping around (ADR 0101). Remaining: the administrator UI |

## Interoperability and migration

| Capability | Status | Remaining work |
|---|---|---|
| OPDS v1 | READY | Keep protocol regression tests |
| OPDS v2 | PARTIAL | Improve search relevance and native authentication presentation |
| Kobo Sync and KEPUB | PARTIAL | Kobo Store proxy merging and device acceptance tests |
| KOReader Sync | READY | Device acceptance tests |
| One-way Komga 1.25 database import | PARTIAL | Finish production acceptance for restrictions, progress, collections, and read lists; never require runtime DB compatibility |
| Docker, Compose, reverse proxy, and base path | READY | Native UI assets and release packaging. The version/release process is documented in `docs/releasing.md`: versions come from annotated git tags rather than a tracked file, the number follows from the commit types since the previous tag, and there is deliberately no automatic tagging and no `CHANGELOG.md`. That document also records that CI being paused (#128) is a real gap in the release process rather than glossing over it |

## Xoboro extensions

These are valuable but do not count against Komga feature coverage.

| Capability | Status | Remaining work |
|---|---|---|
| WebDAV and other remote source adapters | FUTURE | Source read/list/cache/mutation contracts and credential storage |
| Video and audio timelines | FUTURE | Native scanning, metadata, progress, transcoding/delivery, and clients |
| Private Android and iOS applications | FUTURE | Local libraries, remote Xoboro/NAS connections, offline holdings, and native readers |
