# Xoboro native API v1

The native API is the supported boundary for Xoboro web and mobile clients. It
is rooted at `/api/xoboro/v1`, uses JSON request and response bodies, and does
not reproduce Komga DTOs or endpoint shapes.

This document covers authentication, catalog discovery, page and resource
discovery, page and resource delivery, original file download, and read progress
mutation, settings, authentication activity, history, and the native event
stream. OpenAPI contracts remain pending.

## Errors

Native API failures use a stable JSON envelope:

```json
{
  "code": "invalid_credentials",
  "message": "Invalid email or password"
}
```

Clients must branch on `code`, not the human-readable `message`. Malformed JSON
returns `400 invalid_request`, missing or invalid authentication returns
`401 authentication_required`, and login throttling returns
`429 rate_limit_exceeded` with a `Retry-After` header.
Read progress conflicts return `409 stale_progress`.

## Session transports

`COOKIE` is intended for Xoboro's same-origin web application. The server sets
an `HttpOnly` and `SameSite=Strict` cookie named `XOBORO-SESSION`; it also adds
`Secure` when the verified request origin is HTTPS. The token is not included
in the JSON response.

Cookie-authenticated mutations require either an exact same-origin `Origin`
header or `Sec-Fetch-Site: same-origin`. Requests without browser provenance
and requests identified as cross-site are rejected. A reverse proxy must be in
`XOBORO_TRUSTED_PROXIES` before its forwarded origin is trusted.

`BEARER` is intended for native and non-browser clients. The plain access token
is returned once in the response and is then sent as:

```text
Authorization: Bearer <accessToken>
```

Bearer mutations do not use browser CSRF headers. Xoboro stores only a digest
of either transport's token, so a database copy cannot be used as a session.

## Setup

### Inspect setup state

`GET /api/xoboro/v1/setup`

```json
{
  "claimed": false
}
```

### Claim an unconfigured server

`POST /api/xoboro/v1/setup`

```json
{
  "email": "admin@example.invalid",
  "password": "a-long-unique-password",
  "transport": "COOKIE"
}
```

The first successful request creates an administrator and returns
`201 Created` with a session. Later attempts return
`409 server_already_claimed`.

## Sessions

### Sign in

`POST /api/xoboro/v1/session`

```json
{
  "email": "reader@example.invalid",
  "password": "a-long-unique-password",
  "transport": "BEARER"
}
```

A successful bearer response is:

```json
{
  "user": {
    "id": "01HZX...",
    "email": "reader@example.invalid",
    "roles": ["USER"]
  },
  "accessToken": "<returned-once>"
}
```

Password attempts are limited to ten per minute for each verified client IP by
default. Rejected attempts consume the same budget as successful attempts.

### Inspect the current session

`GET /api/xoboro/v1/session`

Authenticate with the session cookie or bearer header. The response contains
the current user and omits the access token.

### Sign out

`DELETE /api/xoboro/v1/session`

The server immediately revokes the presented session. Cookie responses also
expire `XOBORO-SESSION`. Successful logout returns `204 No Content`.

## Pagination and sorting

Catalog collections return:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalItems": 0,
  "totalPages": 0,
  "hasPrevious": false,
  "hasNext": false
}
```

`page` is zero-based and `size` must be between 1 and 200. Repeat `sort` to
apply multiple stable sorts. Each value uses `field[,asc|desc]`; unknown fields
and invalid values return `400 invalid_query` instead of being silently
coerced.

Series sorts are `title`, `createdAt`, `updatedAt`, `sourceModifiedAt`,
`lastReadAt`, and `mediaItemCount`. Media-item sorts are `title`,
`seriesTitle`, `number`, `createdAt`, `updatedAt`, `sourceModifiedAt`,
`fileSize`, and `lastReadAt`.

## Libraries

`GET /api/xoboro/v1/libraries` returns libraries visible to the current user,
ordered by name. `GET /api/xoboro/v1/libraries/{libraryId}` returns one visible
library. A missing or unauthorized identifier returns `404` so library grants
cannot be enumerated.

Only administrators receive the source provider and location. Reader accounts
receive `source: null`, preventing local paths and future remote-source
identifiers from leaking through catalog discovery.

## Library administration

All library administration routes require an administrator. An authenticated
non-administrator receives `403 library_administration_forbidden` before the
server looks up a library or reads a request body.

- `POST /api/xoboro/v1/libraries` creates a library and returns `201 Created`.
- `PUT /api/xoboro/v1/libraries/{libraryId}` fully replaces the library name,
  source, and settings and returns `200 OK`.
- `DELETE /api/xoboro/v1/libraries/{libraryId}` returns `204 No Content`. It is
  refused with `409 library_unavailable` while the library storage is
  unavailable, so a catalog is not deleted because a mount went missing. The
  flag is only cleared by a successful scan, so storage that is gone for good
  would otherwise leave the library undeletable; repeat the request with
  `?force=true` to delete it anyway. A `force` value that is not `true` or
  `false` returns `400 invalid_query` rather than being coerced.
- `POST /api/xoboro/v1/libraries/{libraryId}/scan` enqueues a standard scan and
  returns `202 Accepted`.
- `POST /api/xoboro/v1/libraries/{libraryId}/analyze` enqueues analysis and
  returns `202 Accepted`.
- `POST /api/xoboro/v1/libraries/{libraryId}/metadata-refresh` enqueues a
  metadata refresh and returns `202 Accepted`.
- `POST /api/xoboro/v1/libraries/{libraryId}/empty-trash` enqueues trash
  emptying and returns `202 Accepted`.

Create and update use the same request shape:

```json
{
  "name": "Synthetic Library",
  "source": {
    "provider": "local",
    "location": "file:///synthetic/library"
  },
  "settings": {
    "scanOnStartup": true,
    "scanInterval": "DAILY"
  }
}
```

Omitted settings use the server defaults. `PUT` is a full replacement rather
than a partial patch. The four task-trigger endpoints return `202` because
they enqueue durable background work. This deliberately differs from artwork
mutations that return `204` only after completing synchronously.

Library administration failures use these codes:

- `library_administration_forbidden`: the authenticated user is not an
  administrator.
- `library_not_found`: the requested library does not exist.
- `invalid_request`: the JSON or otherwise supplied library data is invalid.
- `library_root_missing`: the supplied source location does not exist.
- `library_root_not_directory`: the supplied source location is not a
  directory.
- `library_name_conflict`: another library already uses the supplied name.
- `library_root_overlap`: the supplied root overlaps another library root.
- `library_unavailable`: deletion is refused while the library storage is
  unavailable. Repeat with `force=true` to override.

## Series

`GET /api/xoboro/v1/series` returns visible, non-deleted series. It accepts:

- repeated `libraryId`, `publisher`, `language`, `genre`, and `tag` filters;
- `query` for full-text search;
- `oneShot=true|false`;
- pagination and series sorts described above.

`GET /api/xoboro/v1/series/{seriesId}` returns one visible series.
`GET /api/xoboro/v1/series/{seriesId}/media-items` returns its visible
non-deleted items, ordered by number unless an explicit sort is provided.

## Media items

`GET /api/xoboro/v1/media-items` returns visible non-deleted items and accepts
repeated `libraryId`, optional `seriesId`, `query`, `onDeck`, `keepReading`,
pagination, and media-item sorts.

`GET /api/xoboro/v1/media-items/{mediaItemId}` returns one item. Append
`/previous` or `/next` for sequential navigation within the authorized
catalog.

The response uses the common Xoboro media hierarchy. Existing storage is
classified as `COMIC` for comic archives, `NOVEL` for EPUB, and `BOOK` for
PDF. The same contract can later add `VIDEO` and `AUDIO` without introducing
a second catalog API.

## Media delivery

All media-delivery routes require authentication. Page and EPUB-resource
delivery require the `PAGE_STREAMING` role; original file download requires
`FILE_DOWNLOAD`. Page numbers are one-based. There is no `zero_based` query
parameter on the native surface.

`GET /api/xoboro/v1/media-items/{mediaItemId}/pages` returns the indexed page
manifest as a JSON list. Each entry contains its one-based `number`,
`mediaType`, optional `width` and `height`, and optional raw `sizeBytes`.
Internal archive-entry file names are not exposed.

`GET /api/xoboro/v1/media-items/{mediaItemId}/pages/{pageNumber}` returns the
page bytes. It accepts:

- `format=jpeg|png|source`, case-insensitively. `source` works for every media
  kind and returns the stored or embedded page bytes without re-encoding.
- `maxDimension=<positive integer>`, capped at 4096. It can be used without an
  explicit format, or with `jpeg` or `png`.

`format=source` cannot be combined with `maxDimension`. The endpoint does not
perform `Accept`-header format negotiation and deliberately has no
`contentNegotiation` query parameter.

Successful page responses include a strong content-derived `ETag`,
`Last-Modified` from the indexed media update timestamp, and
`Cache-Control: max-age=0, must-revalidate, private`. Clients can revalidate
with `If-None-Match` or `If-Modified-Since`; a match returns `304 Not Modified`.
The page is opened and buffered before its ETag can be evaluated, including
requests that ultimately return 304.

`GET /api/xoboro/v1/media-items/{mediaItemId}/resources` returns indexed EPUB
page and asset entries as a JSON list; general container files are excluded.
Each `path` is the full container-relative archive path and must be used
verbatim. Indexed paths have already been resolved relative to the EPUB OPF
directory, so a client that parses the OPF itself and sends its raw hrefs will
receive 404 responses. Manifest order is stable stored order (the OPF manifest
order), not reading or spine order.

`GET /api/xoboro/v1/media-items/{mediaItemId}/resources/{resource...}` returns
one indexed EPUB-container resource. Send a `path` from the resource manifest
verbatim; a raw OPF-relative href does not resolve. The endpoint is EPUB-only:
CBZ/DiViNa content is represented by its pages and has no separate resources.
Resource resolution is an exact archive-path index lookup, not a filesystem
join, so path traversal is structurally impossible.

Successful resource responses set
`Content-Security-Policy: script-src 'none'; object-src 'none';` because EPUB
resources are user-supplied same-origin content. They do not set
`Content-Disposition`, since resources can be iframe subresources. Resource
bytes use the same strong content-derived ETag, private conditional caching,
and 304 behavior as page bytes. They do not support byte ranges.

`GET /api/xoboro/v1/media-items/{mediaItemId}/file` downloads the original
media file. It uses `Content-Disposition: attachment` with both a safe ASCII
`filename=` fallback and an RFC 5987 UTF-8 `filename*=` parameter.

File responses use the weak validator
`W/"<fileSize>-<fileModifiedAtMillis>"`. The tag is weak because size and
modification time do not prove byte-for-byte identity. Computing it entirely
from authorized catalog metadata avoids opening or buffering a potentially
large or remote file for a conditional request. A matching `If-None-Match`
therefore returns 304 before content access.

The file endpoint advertises `Accept-Ranges: bytes` and supports one fixed,
open-ended, or suffix byte range. A satisfiable range returns 206 with
`Content-Range`; a syntactically valid range starting outside the file returns
416 with `Content-Range: bytes */<fileSize>`. Malformed ranges are ignored.
Multiple ranges deliberately produce a full 200 response rather than Komga's
416 response because the native endpoint does not implement multipart ranges
and a complete representation remains useful. `If-Range` accepts the current
weak ETag or `Last-Modified`; a mismatch ignores the range and sends the full
file, preventing a resumed download from splicing bytes from different file
versions.

Delivery failures use these native error codes:

- `403 page_streaming_forbidden` when the user lacks the required role.
- `403 file_download_forbidden` when the user lacks original-file download
  permission.
- `404 media_item_not_found` for missing or unauthorized media items.
- `404 page_not_found` for a page outside the indexed media range or unavailable
  from the content provider.
- `404 resource_not_found` for a missing, invalid, or non-EPUB resource.
- `409 media_not_ready` when indexed media is not ready for page delivery.
- `409 page_not_decodable` when an existing page cannot be decoded.

Invalid page numbers, formats, and dimensions return `400 invalid_query`.

## Read progress

`PUT /api/xoboro/v1/media-items/{mediaItemId}/progress`

```json
{
  "page": 4,
  "locator": {
    "href": "chapter-2.xhtml",
    "locations": {
      "progression": 0.25
    }
  },
  "deviceId": "synthetic-device",
  "deviceName": "Synthetic reader",
  "modifiedAtMillis": 1735689600000
}
```

`locator` is an opaque JSON object stored with the page position.
`modifiedAtMillis` is the client's own clock and is the sole conflict-ordering
key; the server does not substitute its own clock. A value older than or equal
to the currently stored progress returns `409 stale_progress` without applying
the write.

The endpoint supports the same cookie and bearer transports, including the
same-origin requirements for cookie mutations, described in
[Session transports](#session-transports).

## Artwork

Artwork routes are available under both
`/api/xoboro/v1/media-items/{mediaItemId}` and
`/api/xoboro/v1/series/{seriesId}`:

- `GET /artwork` returns the selected artwork bytes.
- `GET /artworks` returns the stored artwork metadata collection.
- `GET /artworks/{artworkId}` returns one stored artwork's bytes.
- `POST /artworks` uploads and selects a new artwork. It requires an
  administrator and returns `201 Created` with the stored artwork.
- `PUT /artworks/{artworkId}/selected` selects stored artwork. It requires an
  administrator and returns `204 No Content`.
- `DELETE /artworks/{artworkId}` deletes uploaded artwork. It requires an
  administrator and returns `204 No Content`.

Singular `artwork` means the selected image; plural `artworks` means the
collection of stored images. A missing selected image or identifier returns
`404 artwork_not_found`. There is no page render fallback: artwork is produced
during scanning, so absence is a real state rather than an image to synthesize
on every request.

Uploads larger than 20 MiB return `413 artwork_too_large`. A non-empty upload
whose image format cannot be processed returns `415 artwork_not_supported`.
Select and delete use `204`, not `202`, because each operation is complete
before the response is sent.

## Collections and read lists

Collections organize series; read lists organize media items. The native API
uses `mediaItemIds`, not `bookIds`, and `/media-items`, not `/books`.

| Method | Path | Success | Administrator |
| --- | --- | --- | --- |
| `GET` | `/api/xoboro/v1/collections` | `200 OK` | No |
| `POST` | `/api/xoboro/v1/collections` | `201 Created` | Yes |
| `GET` | `/api/xoboro/v1/collections/{collectionId}` | `200 OK` | No |
| `PUT` | `/api/xoboro/v1/collections/{collectionId}` | `200 OK` | Yes |
| `DELETE` | `/api/xoboro/v1/collections/{collectionId}` | `204 No Content` | Yes |
| `GET` | `/api/xoboro/v1/collections/{collectionId}/series` | `200 OK` | No |
| `GET` | `/api/xoboro/v1/read-lists` | `200 OK` | No |
| `POST` | `/api/xoboro/v1/read-lists` | `201 Created` | Yes |
| `GET` | `/api/xoboro/v1/read-lists/{readListId}` | `200 OK` | No |
| `PUT` | `/api/xoboro/v1/read-lists/{readListId}` | `200 OK` | Yes |
| `DELETE` | `/api/xoboro/v1/read-lists/{readListId}` | `204 No Content` | Yes |
| `GET` | `/api/xoboro/v1/read-lists/{readListId}/media-items` | `200 OK` | No |
| `GET` | `/api/xoboro/v1/series/{seriesId}/collections` | `200 OK` | No |
| `GET` | `/api/xoboro/v1/media-items/{mediaItemId}/read-lists` | `200 OK` | No |

Collection create and update requests use `name`, `ordered`, and `seriesIds`.
Read-list requests use `name`, `summary`, `ordered`, and `mediaItemIds`.
`ordered` defaults to `true` on create, and read-list `summary` defaults to an
empty string on create. Detail and list responses do not embed member
identifiers; ordered members are available only through the dedicated member
routes.

`PUT` is a full replacement of the name, ordering flag, member list, and member
order, not a merge. Read-list updates also fully replace the summary. A create
or update is rejected in full if any submitted member is missing or invisible
under the administrator's own library grants and content restrictions.

Member routes use the native page envelope and accept zero-based `page` and a
`size` from 1 through 200. They do not accept `sort`: the stored member order is
the contract. Visibility filtering removes unauthorized members while
preserving the remaining members' stored relative order.

The same visibility rule applies to list, detail, member, and reverse-lookup
responses. `memberCount` is the number of members visible to the caller, not
the stored total. If every stored member is invisible, detail and member
routes return the same `404` as a missing resource rather than an empty
response. This prevents callers from using the difference between an empty
response and a missing identifier to enumerate collections or read lists they
cannot see.

Collection and read-list failures use these native error codes:

- `400 invalid_request` for malformed JSON, invalid fields, domain validation
  failures, or a submitted member that is missing or invisible.
- `400 invalid_query` for invalid member pagination, a size over 200, or a
  `sort` parameter on an ordered member route.
- `401 authentication_required` when no valid cookie or bearer session is
  supplied.
- `403 collection_administration_forbidden` when a non-administrator calls a
  collection mutation.
- `403 read_list_administration_forbidden` when a non-administrator calls a
  read-list mutation.
- `403 cross_site_request_rejected` for a cookie-authenticated mutation
  without trusted same-origin provenance.
- `404 collection_not_found` when a collection is missing or has no members
  visible to the caller.
- `404 read_list_not_found` when a read list is missing or has no members
  visible to the caller.
- `404 series_not_found` when a reverse lookup targets a missing or invisible
  series.
- `404 media_item_not_found` when a reverse lookup targets a missing or
  invisible media item.
- `409 collection_name_conflict` when another collection already uses the
  requested name.
- `409 read_list_name_conflict` when another read list already uses the
  requested name.

Collection/read-list artwork and ComicRack CBL import are outside this feature.
There is no native collection/read-list artwork resource yet, and CBL import is
a separate migration and interoperability concern.

## Users and API keys

Authenticated users have a self-service surface that never accepts a user
identifier. This separation prevents a non-administrator from selecting or
acting on another account:

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/me` | `200 OK` |
| `PUT` | `/api/xoboro/v1/me/password` | `204 No Content` |
| `GET` | `/api/xoboro/v1/me/api-keys` | `200 OK` |
| `POST` | `/api/xoboro/v1/me/api-keys` | `201 Created` |
| `DELETE` | `/api/xoboro/v1/me/api-keys/{apiKeyId}` | `204 No Content` |

`PUT /me/password` requires both `currentPassword` and `newPassword`. An
incorrect current password does not change the password or invalidate
sessions. Every successful password change invalidates all existing sessions
for that user.

`POST /me/api-keys` accepts a non-empty `comment`. Its response includes the
plaintext key as `token` exactly once. Xoboro stores a digest, not the
plaintext value, so the token cannot be retrieved again. `GET /me/api-keys`
returns metadata only: identifier, comment, and creation/update timestamps.
Deleting a missing key or a key owned by another user returns the same
`404 api_key_not_found` response.

User administration is a separate administrator-only surface:

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/users` | `200 OK` |
| `POST` | `/api/xoboro/v1/users` | `201 Created` |
| `PUT` | `/api/xoboro/v1/users/{userId}` | `200 OK` |
| `PUT` | `/api/xoboro/v1/users/{userId}/password` | `204 No Content` |
| `DELETE` | `/api/xoboro/v1/users/{userId}` | `204 No Content` |

User responses contain account identity, roles, library grants, content
restrictions, and timestamps; password hashes are never returned.
`PUT /users/{userId}` replaces roles, shared-library identifiers,
`sharesAllLibraries`, and restrictions exactly as submitted. Unknown role names
and invalid library identifiers return `400 invalid_request` and change nothing;
they are not dropped, because a mistyped role would otherwise grant fewer
rights than requested while still answering with success. An administrator
password reset accepts only `newPassword`; it does not require the target
user's current password. Like a self-service password change, it invalidates
all of that user's existing sessions.

Administrators cannot delete their own account. Xoboro also prevents deleting
or demoting the sole remaining administrator. Changing or deleting an
administrator is allowed when another administrator remains.

User and API-key operations use these error codes:

- `400 invalid_request` for malformed JSON, invalid account fields, blank
  passwords, or a blank API-key comment.
- `401 authentication_required` when no valid cookie or bearer session is
  supplied.
- `403 user_administration_forbidden` when a non-administrator calls a user
  administration route.
- `403 invalid_credentials` when `PUT /me/password` receives the wrong current
  password.
- `403 cross_site_request_rejected` for a cookie-authenticated mutation
  without trusted same-origin provenance.
- `404 user_not_found` when an administration target does not exist.
- `404 api_key_not_found` when a self-service deletion target is missing or is
  owned by another user.
- `409 user_email_already_exists` when an account already uses the requested
  email.
- `409 duplicate_api_key_comment` when the caller already has an API key with
  the requested comment.
- `409 cannot_delete_own_account` when an administrator targets their own
  account for deletion.
- `409 last_administrator_protected` when a request would delete or demote the
  sole remaining administrator.
- `503 api_key_generation_failed` when unique key generation exhausts all
  attempts.

## Server, client settings, and operational activity

All routes in this section require a valid cookie or bearer session.
Administrator-only routes reject an authenticated non-administrator before
reading query parameters or a request body.

### Server settings

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/server-settings` | `200 OK` |
| `PUT` | `/api/xoboro/v1/server-settings` | `204 No Content` |

`GET /server-settings` returns exactly these settings:
`deleteEmptyCollections`, `deleteEmptyReadLists`, `rememberMeDurationDays`,
`thumbnailSize`, `taskPoolSize`, `serverPort`, `serverContextPath`,
`koboProxy`, `koboPort`, and `kepubifyPath`. The `serverPort`,
`serverContextPath`, and `kepubifyPath` values include their
`configurationSource`, `databaseSource`, and `effectiveValue`.

GET /server-settings never returns the remember-me signing key; PUT accepts
`renewRememberMeKey` to rotate it without ever exposing its value. Fields absent
from a PUT body are left unchanged. An explicit `null` clears the database
override for `serverPort`, `serverContextPath`, `koboPort`, or `kepubifyPath`;
the other optional fields treat absence or `null` as no change.

Malformed JSON, unknown thumbnail sizes, and invalid setting values return
`400 invalid_request`. A non-administrator receives
`403 server_settings_forbidden`.

### Client settings

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/client-settings` | `200 OK` |
| `PUT` | `/api/xoboro/v1/client-settings` | `204 No Content` |
| `GET` | `/api/xoboro/v1/client-settings/global` | `200 OK` |
| `PUT` | `/api/xoboro/v1/client-settings/global` | `204 No Content` |
| `DELETE` | `/api/xoboro/v1/client-settings/global` | `204 No Content` |

`GET /client-settings` merges the global settings with the caller's per-user
settings. A per-user value overrides a global value with the same key.
`PUT /client-settings` writes only the caller's scope and accepts a map whose
values contain `value`. The global routes require an administrator. Global
writes also require `allowUnauthorized` for every value, and global deletion
accepts a JSON array of unique keys.

Invalid keys, blank values, and malformed JSON return `400 invalid_request`.
A non-administrator receives `403 client_settings_forbidden` from a global
route.

### Authentication activity and history

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/authentication-activity` | `200 OK` |
| `GET` | `/api/xoboro/v1/me/authentication-activity` | `200 OK` |
| `GET` | `/api/xoboro/v1/history` | `200 OK` |

The administrator authentication-activity route returns activity across all
users. The `/me/authentication-activity` route is available to every
authenticated caller and returns only that caller's rows. Authentication
activity exposes identity and request metadata, success or error state, source,
and `dateTimeMillis`; it never contains raw API-key material.

Authentication activity sorts are `dateTime`, `email`, `success`, `ip`,
`error`, `userId`, and `userAgent`. History sorts are `type`, `bookId`,
`seriesId`, and `timestamp`. Both resources use the native page envelope,
zero-based `page`, and `size` from 1 through 200. A sort is
`field[,asc|desc]` and defaults to descending timestamp order. Invalid paging
or sorting returns `400 invalid_query`.

The all-user authentication activity route returns
`403 authentication_activity_forbidden` to non-administrators. History returns
`403 history_forbidden` to non-administrators.

Every route in this section returns `401 authentication_required` without a
valid session. Cookie-authenticated `PUT` and `DELETE` requests without trusted
same-origin provenance return `403 cross_site_request_rejected`.

## Metadata and facets

Metadata editing is available to any authenticated caller for content visible
through that caller's normal catalog access. It is not administrator-gated.
Missing and unauthorized content are deliberately indistinguishable: both
return `404` with `media_item_not_found` for media items or `series_not_found`
for series.

- `PATCH /api/xoboro/v1/media-items/{mediaItemId}/metadata` returns `200 OK`
  with the updated book metadata.
- `PATCH /api/xoboro/v1/media-items/metadata` returns `200 OK` with the updated
  book metadata array.
- `PATCH /api/xoboro/v1/series/{seriesId}/metadata` returns `200 OK` with the
  updated series metadata.
- `GET /api/xoboro/v1/facets?facet={facet}` returns `200 OK` with the visible
  values as a JSON array.
- `GET /api/xoboro/v1/facets/authors` returns `200 OK` with a paginated author
  response.

For fields represented as patch fields, an absent property preserves the
stored value, while an explicit JSON `null` clears it. Clearing a non-nullable
string or collection stores its empty value; clearing a nullable scalar stores
`null`. Plain optional properties treat absence and `null` alike and preserve
the stored value. Lock properties are stored alongside metadata for refresh
pipelines to respect. They do not prevent a later manual patch from changing
the locked field.

The bulk media-item endpoint accepts at most 200 entries. It validates catalog
visibility for every requested identifier before applying any write. If any
identifier is missing or unauthorized, the whole request returns `404
media_item_not_found` and nothing is changed.

Authorization is therefore all-or-nothing, but a patch can still fail while the
batch is being applied. When fewer entries are stored than were requested the
endpoint returns `409 bulk_patch_incomplete` rather than `200` with a shorter
array, because a short success would report that edits happened when they did
not. That response means the batch is **partially applied**: re-read the
requested media items to find the current state.

Facet values and authors are scoped to the caller's granted libraries and
content restrictions. `/facets` requires one case-sensitive `facet` value:
`genre`, `seriesTag`, `bookTag`, `language`, `publisher`, `ageRating`,
`sharingLabel`, or `releaseYear`. Both facet routes accept an optional repeated
`libraryId` filter.

## Task queue

`GET /api/xoboro/v1/tasks` returns the durable queue's counts and requires an
administrator:

```json
{ "pending": 3, "running": 1, "dead": 2 }
```

Two routes discard tasks by state, each honest about which state it targets.
Both answer `200` with the number removed rather than `204`, because the count
is the useful part of the reply for an administrator clearing a backlog, and
both return `403 task_administration_forbidden` for a non-administrator with
the check running before the queue is consulted, so a refused request never
reaches it:

```json
{ "cleared": 7 }
```

`DELETE /api/xoboro/v1/tasks/unclaimed` discards queued work that no worker
has claimed (`PENDING`) and requires an administrator. Running and dead tasks
are untouched - only unclaimed work is discarded, matching what the route's
name says.

`DELETE /api/xoboro/v1/tasks/dead` discards tasks that have permanently failed
(`DEAD`) and requires an administrator. Pending and running tasks are
untouched. Task ids are deterministic, and a dead task revives back to
`PENDING` on its next enqueue without resetting its attempt count (see the
durable queue's `enqueue` documentation), so a task that keeps dying costs one
attempt per re-enqueue rather than a fresh budget. This endpoint removes the
row entirely so the next enqueue starts that budget over, without discarding
any unrelated work still queued to run.

## Operational metrics

`GET /api/xoboro/v1/metrics` requires an administrator and returns a bounded,
in-process operational snapshot as JSON:

```json
{
  "ready": true,
  "uptimeSeconds": 812.4,
  "activeRequests": 1,
  "totalRequests": 4032,
  "requestsByStatusClass": { "2xx": 3990, "4xx": 40, "5xx": 2 },
  "taskQueue": { "pending": 3, "running": 1, "dead": 2 },
  "taskWorkerCount": 4
}
```

This is separate from the pre-existing bounded Prometheus scrape at the
unversioned `/metrics` path (see ADR 0053), which is gated by a static
`XOBORO_METRICS_TOKEN` bearer secret for external scrapers. The native
endpoint answers to any authenticated administrator session so an
administration UI does not need a separate scrape secret; both surfaces read
from the same in-process counters. A non-administrator receives
`403 operational_metrics_forbidden`.

## Backups

Backup files are stored under a server-controlled directory
(`XOBORO_BACKUPS_PATH`, default `config/backups`) and are only ever referenced
by an opaque ID — the API never returns an absolute filesystem path. All
backup routes require an administrator.

| Method | Path | Success |
| --- | --- | --- |
| `POST` | `/api/xoboro/v1/backups` | `201 Created` |
| `GET` | `/api/xoboro/v1/backups` | `200 OK` |
| `DELETE` | `/api/xoboro/v1/backups/{backupId}` | `204 No Content` |

`POST /backups` runs a `VACUUM INTO` snapshot of the live catalog database and
verifies its integrity before returning. This is a bounded, single SQL
statement rather than a filesystem walk, so unlike library scans it completes
synchronously and answers `201 Created` with the descriptor once done, not
`202 Accepted`:

```json
{ "id": "0f8a3c1e9b7d4a2f", "sizeBytes": 10485760, "createdAtMillis": 1732900000000 }
```

`GET /backups` lists every stored backup, most recent first. `DELETE
/backups/{backupId}` removes one; an unknown ID returns `404 backup_not_found`.
A non-administrator receives `403 backup_administration_forbidden` before any
backup is read, created, or deleted.

Restoring a backup is intentionally **not** exposed over HTTP: restoring
requires taking the live database file offline, which conflicts with the
running server's own file lock. Restore remains an operator action via the
`xoboro restore <path>` CLI command, alongside the existing `backup` and
`verify-backup` commands.

## Catalog maintenance

These commands queue the same durable analysis and metadata-refresh work as
the per-library maintenance routes, scoped to a single media item or series.
All routes require an administrator.

| Method | Path | Success |
| --- | --- | --- |
| `POST` | `/api/xoboro/v1/media-items/{mediaItemId}/analyze` | `202 Accepted` |
| `POST` | `/api/xoboro/v1/media-items/{mediaItemId}/metadata-refresh` | `202 Accepted` |
| `POST` | `/api/xoboro/v1/series/{seriesId}/analyze` | `202 Accepted` |
| `POST` | `/api/xoboro/v1/series/{seriesId}/metadata-refresh` | `202 Accepted` |

A missing media item returns `404 media_item_not_found`; a missing series
returns `404 series_not_found`. A non-administrator receives `403
catalog_maintenance_forbidden` before any existence check or task is queued.

Duplicate-page removal and book-artwork regeneration are intentionally not
ported to the native surface yet: they are tracked separately under artwork
and duplicate-detection work in `docs/feature-coverage.md` rather than as
general "maintenance" commands.

> **Known caveat:** `Application.kt` installs a global `StatusPages
> status(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)` handler that
> rewrites every native 404/403 body to a generic `{"code":"not_found"}` or
> `{"code":"forbidden"}`, discarding the specific code a route already set
> (`backup_not_found`, `media_item_not_found`, `series_not_found`, and every
> other existing native `*_not_found`/`*_forbidden` code, including
> pre-existing routes such as the metadata PATCH endpoints). This was verified
> against the real production wiring while adding this section and is a
> pre-existing defect that predates this change; it is not fixed here because
> correcting it touches the shared error-handling pipeline for the entire
> native API. Route-level tests intentionally exercise routes directly and
> therefore still assert the specific codes documented above.

## Events

`GET /api/xoboro/v1/events` opens a native Server-Sent Events stream scoped to
the authenticated caller's current library grants and content restrictions.
It supports both session transports described in
[Session transports](#session-transports): cookie and bearer. Because a
cookie-authenticated `EventSource` cannot set an `Authorization` header but
does send ambient same-origin cookies, cookie transport on this endpoint
requires the same same-origin provenance (an exact `Origin` match or
`Sec-Fetch-Site: same-origin`) as a cookie-authenticated mutation; a request
without it is rejected before the stream opens. Bearer transport carries no
ambient cookie and is exempt, exactly like mutations are.

### Resuming with `Last-Event-ID`

Every frame carries a resumable `id`. Reconnecting with the `Last-Event-ID`
header set to the last `id` the client observed drives one of these outcomes:

- No `Last-Event-ID` at all: a fresh connection, `stream.ready
  {"resumed":false}`.
- An `id` from a previous server process, an unparseable `id`, or an `id`
  older than the server's replay buffer: `stream.resync-required
  {"reason":"gap","seq":<current>}`, with no replay.
- An `id` already caught up to the current position: `stream.ready
  {"seq":<current>,"resumed":true}`.
- An `id` still inside the replay buffer: every event the caller is currently
  authorized to see that was published after that `id`, followed by
  `stream.ready {"seq":<current>,"resumed":true}`.

**Freshness guarantee:** a client that has received `stream.ready
{"resumed":true}` and has not, since that frame, received a
`stream.resync-required` frame has provably missed nothing it was entitled to
see. A `stream.resync-required` frame is the only way the server ever tells a
client its view may be stale; anything else — including a gap in `seq` — is
not evidence of loss.

### Control events

- `stream.ready` — `{"seq": <number, optional>, "resumed": <boolean>}`. `seq`
  is present only when `resumed` is `true`.
- `stream.resync-required` — `{"reason": "gap"|"overflow"|"superseded",
  "seq": <number, optional>}`. `reason: "gap"` means the client's
  `Last-Event-ID` could not be resumed (see above) and carries the server's
  current `seq`. `reason: "overflow"` means the client's own delivery queue on
  the server fell behind and was discarded to avoid delivering a truncated
  burst as if it were complete; reconnect without assuming anything about
  what was missed. `reason: "superseded"` means this specific connection was
  closed to make room under the per-user stream limit (a client opening a
  fifth stream evicts its own oldest); it is not an authorization or capacity
  problem with the *new* connection. `overflow` and `superseded` frames carry
  no `seq`, because neither promises a resumable position — only that the
  stream is continuing (`overflow`) or ending (`superseded`) from here. A
  stream that is re-authenticated away because the subscriber's authorization
  changed (role, library grants, restrictions, or email) also arrives as
  `stream.resync-required {"reason":"revoked"}`, with the connection closing
  immediately after.

🔴 **Do not infer loss from gaps in `seq`.** `seq` is a shared counter
incremented once per event *published*, before any per-subscriber filtering —
not once per event *delivered*. A subscriber whose grants exclude some
libraries will legitimately observe `seq` values like 1837 then 1904 for two
consecutive events it receives, because 66 other subscribers' events were
published in between and correctly filtered out before reaching it. That is
normal, not loss. The only trustworthy loss signal is a `stream.resync-required`
frame.

**Clients must ignore unknown event names.** This is what allows the server to
add a new event name in a later release without that addition being a
breaking change: a client that fails, disconnects, or otherwise reacts
specially to a name it does not recognize turns every future additive change
into a breaking one for that client.

### Domain events

Every domain event payload carries identifiers only — never an entity
snapshot — so a client must re-fetch the current state through the regular
catalog routes rather than trust anything beyond the identifiers on the
event. `ids` is always a JSON array, even for a single identifier. **A client
that receives a large batch of `ids` should invalidate the affected view and
re-query it, not fetch each id individually** — the event is an invalidation
signal, not a change log to replay item by item.

| Event | Payload | Scope |
| --- | --- | --- |
| `library.added` / `library.changed` / `library.removed` | `{"ids":[libraryId]}` | The library itself |
| `media-item.added` / `media-item.changed` / `media-item.removed` | `{"ids":[mediaItemId],"libraryId":libraryId,"seriesId":seriesId}` | Its library, then the item itself for restricted subscribers |
| `series.added` / `series.changed` / `series.removed` | `{"ids":[seriesId],"libraryId":libraryId}` | Its library, then the series itself for restricted subscribers |
| `collection.added` / `collection.changed` / `collection.removed` | `{"ids":[seriesId, ...]}` (the collection's member series) | Visible if at least one member series is visible |
| `read-list.added` / `read-list.changed` / `read-list.removed` | `{"ids":[mediaItemId, ...]}` (the read list's member items) | Visible if at least one member item is visible |
| `read-progress.changed` / `read-progress.removed` | `{"ids":[mediaItemId]}` | The subscriber that owns the progress, only |
| `series-progress.changed` / `series-progress.removed` | `{"ids":[seriesId]}` | The subscriber that owns the progress, only |
| `poster.changed` | `{"ids":[ownerId],"ownerKind":"MEDIA_ITEM"\|"SERIES"}` | The owning media item or series |

**The `*.removed` rule:** a removal is scoped by library grant only — the
per-item restriction recheck that gates `*.added`/`*.changed` for a
content-restricted subscriber is skipped for `*.removed`, because the row is
already gone by the time the removal is published and a "still visible"
recheck would find nothing and deliver the removal to nobody. A subscriber
who can access the library therefore learns that *something* it could not
necessarily see was removed; this is bounded to the same disclosure as an
item that was added and removed while the subscriber was away, and it is what
lets every client drop a phantom catalog entry instead of holding it forever.

**`poster.changed` is not emitted when the artwork's owner is a collection or
a read list.** Both owner kinds would need their member series or media items
resolved to scope the event honestly, and the event does not carry that
membership. A client that shows a collection or read-list cover will
therefore show a stale one until it refetches for an unrelated reason (e.g.
paging back to that view); this is a known, deliberate gap, not an omission
to route around client-side.

### Connection limits and reverse-proxy requirements

- Each user may hold at most 4 concurrent streams by default; opening a fifth
  closes the oldest with `stream.resync-required {"reason":"superseded"}`
  rather than rejecting the new connection.
- The server accepts at most 256 concurrent streams in total by default. A
  connection attempt past that limit receives `503 event_stream_capacity` with
  a `Retry-After` header rather than being queued.
- Each stream has a maximum lifetime (1 hour by default). The server simply
  ends the connection once it is reached, without a `stream.resync-required`
  frame — this is an ordinary disconnect, not a revocation, and the client's
  normal reconnect-with-`Last-Event-ID` logic resumes it (cleanly if the
  reconnect happens within the replay buffer window, or with `reason: "gap"`
  otherwise).
- The server sends a heartbeat as an SSE comment (no `event` or `data`) every
  15 seconds by default. A reverse proxy in front of this endpoint **must
  not** buffer or gzip the stream (the response already sends
  `Cache-Control: no-cache, no-transform` and `X-Accel-Buffering: no` to say
  so) and **must** set its idle/read timeout above the heartbeat interval —
  for nginx, `proxy_read_timeout` must exceed 15 seconds, or the proxy will
  close idle-looking connections the application considers healthy.
