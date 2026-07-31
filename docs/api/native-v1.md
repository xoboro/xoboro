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
    "roles": ["PAGE_STREAMING"]
  },
  "accessToken": "<returned-once>"
}
```

`roles` carries `UserRole` names: `ADMIN`, `FILE_DOWNLOAD`, `PAGE_STREAMING`,
`KOBO_SYNC`, `KOREADER_SYNC`. There is no `USER` role — a plain reader holds only
the capability roles it was granted, and an account with none has an empty list.
`transport` defaults to `COOKIE` when omitted.

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

## Machine-readable description

`GET /api/xoboro/v1/openapi.yaml` serves an OpenAPI 3.1 description of every native
endpoint, and `GET /api/xoboro/v1/docs` renders it for a browser. Both are
unauthenticated: they contain no catalog data and no configuration, and requiring a
session to discover how to create one would be circular.

The description is served verbatim from the file committed at
`server/app/src/main/resources/openapi/xoboro-native-v1.yaml`, so what a client reads is
exactly what is reviewed in the repository. Its path and method coverage cannot drift:
`XoboroNativeOpenApiContractTest` walks Ktor's routing tree and fails if a route has no
entry, or an entry has no route.

It is the machine-readable surface - paths, methods, parameters, authentication and status
codes. Request and response bodies, error-code meanings and cache semantics are described
in prose here, in this document.

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

`unavailable` reports whether the library storage is currently unreachable, and
`unavailableSinceMillis` when the outage was first observed (`null` while the
library is available). The timestamp is what distinguishes a mount that dropped a
moment ago from one that has been gone for a week.

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
- `POST /api/xoboro/v1/libraries/{libraryId}/availability` re-checks whether the
  library storage is reachable and returns `200 OK` with the updated library.

The availability check exists because the unavailable flag is otherwise only
cleared by a successful scan, which is expensive on a large library and whose
failure is what set the flag to begin with. It answers "is the mount back?"
directly, and clearing the flag is what makes a plain `DELETE` stop being
refused.

It applies the same test as the scan: the root must be a readable directory. A
root that exists but cannot be read counts as unavailable, so a check that
reports available cannot be contradicted by the next scan. A library whose
source adapter is not installed also counts as unavailable rather than failing
the request - the storage genuinely cannot be read.

Unlike the four task triggers above it is synchronous and returns `200` rather
than `202`: the work is a single check of the library root, and the resulting
state is the point of the request.

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

`GET /api/xoboro/v1/series` returns visible series. It accepts:

- repeated `libraryId`, `publisher`, `language`, `genre`, and `tag` filters;
- `query` for full-text search;
- `oneShot=true|false`;
- `trashed=true|false`, default `false`;
- pagination and series sorts described above.

`trashed` selects between live and trashed entries; it never returns both.
Reconciliation soft-deletes what disappeared from storage, and `empty-trash`
then destroys it, so `trashed=true` is how an operator sees what a scan removed
before agreeing to lose it. There is no restore endpoint and none is needed: a
later scan that finds the files again clears the flag itself. The parameter is
named for the state rather than the column behind it, because these are exactly
the entries that are not gone yet. Media-item listings accept it too.

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

`GET /api/xoboro/v1/media-items/{mediaItemId}/positions` returns the EPUB's
spine-derived reading positions, ascending. **This is the only response that
carries reading order**, and it exists because the resource manifest deliberately
does not: a reader that followed manifest order would present chapters in
whatever sequence the packager wrote them.

Each entry has `position` (one-based), `href`, `mediaType`, `progression` within
that resource, `totalProgression` through the publication, and `koboSpan` for a
KEPUB. `href` matches a resource-manifest `path` and is what the resource route
is asked for verbatim. These are the fields a client copies into the `locator` the
read-progress endpoint accepts.

`totalProgression` is computed as `position / count`, which makes it the progress at
the **end** of that position rather than at its start: the first of two positions
reports `0.5` and the last reports `1.0`. A Readium locator's `totalProgression` is
`0` at the start of a publication, so this value is one position ahead of that
convention. It is documented rather than corrected because the same number already
feeds Kobo's `ProgressPercent`, so changing the arithmetic would move reported
progress for every existing book — a decision on its own rather than a detail of
adding a reader. Treat it as "how far through the publication this position ends".

The list is returned in stored order without sorting, because `BookMedia` requires
positions to be exactly `1..n` in sequence — a sort could not reorder anything and
would only imply a risk the domain has already ruled out.

A non-EPUB media item returns an empty list rather than an error; it has pages, not
positions. An EPUB whose media is not `READY` is a different case and answers
`409 media_not_ready` (or `409 media_unsupported`), exactly as `/pages/{pageNumber}`
does — an empty list for it would tell a reader the book has no content when the
truth is that its content is not known yet. `PAGE_STREAMING` is required, and an
unauthorized identifier returns `404 media_item_not_found` like every other delivery
route.

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
- `409 media_not_ready` when indexed media is not ready for page delivery and a
  later attempt may succeed.
- `409 media_unsupported` when the media item can never be delivered from the
  file on disk. An encrypted archive or document produces this; retrying will not
  help, the file has to be replaced.
- `409 page_not_decodable` when an existing page cannot be decoded.

A media item's `media.status` and `media.message` explain its analysis outcome.
`UNSUPPORTED` with `ERR_1101` means the content is encrypted, and is deliberately
distinct from `ERROR` with `ERR_1008`, which means the container could not be read
at all. `ERR_1102` marks one volume of a multipart archive whose siblings are
missing. `ERR_1006` means no pages were found and `ERR_1007` lists entries that
failed detection. See ADR 0089.

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

`POST /me/api-keys` accepts a non-empty `comment` and optionally `scopes` and
`expiresAtMillis`. Its response includes the plaintext key as `token` exactly
once. Xoboro stores a digest, not the plaintext value, so the token cannot be
retrieved again. `GET /me/api-keys` returns metadata only: identifier, comment,
scopes, expiry, and creation/update timestamps. Deleting a missing key or a key
owned by another user returns the same `404 api_key_not_found` response.

`scopes` narrows the key to a subset of the caller's own roles. Omitting it, or
sending an empty list, leaves the key as capable as its owner — which is how every
key created before scoping existed continues to behave. A scope naming a role the
caller does not currently hold is rejected with `400 invalid_request`, because at
creation time an unsatisfiable scope is a mistake worth reporting. At
authentication the same mismatch is expected — the owner's roles may have been
reduced since the key was issued — and the key is silently narrowed instead, so a
stored scope can only ever remove capability, never grant it.

The scope is applied by narrowing the authenticated caller itself, not by a second
set of per-route checks. Every role check and every visibility filter downstream
reads that narrowed caller, so a scope holds on the native API and on every
protocol adapter without either having to know a key was involved.

`expiresAtMillis` is an absolute instant in epoch milliseconds, not a duration, and
must be in the future. A key stops authenticating at the instant it names —
expiry is decided when the key is presented, not by a background sweep, so it does
not depend on a job having run. An expired key is still **listed** by
`GET /me/api-keys` with its `expiresAtMillis` in the past, so its owner can see
which key stopped working and delete it deliberately.

The Komga-compatible `/api/v1/users/me/api-keys` surface has no scope or expiry
field and creates unscoped, non-expiring keys, matching the contract Komga clients
expect (ADR 0082).

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
`koboProxy`, `koboPort`, `kepubifyPath`, `historyRetentionDays`, and
`authenticationActivityRetentionDays`. The `serverPort`, `serverContextPath`,
and `kepubifyPath` values include their `configurationSource`,
`databaseSource`, and `effectiveValue`.

GET /server-settings never returns the remember-me signing key; PUT accepts
`renewRememberMeKey` to rotate it without ever exposing its value. Fields absent
from a PUT body are left unchanged. An explicit `null` clears the database
override for `serverPort`, `serverContextPath`, `koboPort`, or `kepubifyPath`;
the other optional fields treat absence or `null` as no change.

Malformed JSON, unknown thumbnail sizes, and invalid setting values return
`400 invalid_request`. A non-administrator receives
`403 server_settings_forbidden`.

#### When a change takes effect

Most settings take effect on the next operation that reads them. These do not:

| Setting | When it takes effect |
| --- | --- |
| `serverPort` | **Restart.** The listener is bound at startup. `effectiveValue` reports the port actually in use, so a pending change is visible as `databaseSource` differing from `effectiveValue`. |
| `serverContextPath` | **Restart**, for the same reason: routes are mounted once. |
| `koboPort` | **Restart.** |
| `kepubifyPath` | **Restart.** The binary is resolved once at startup so that a missing or non-executable path fails loudly then, rather than on a reader's first Kobo download. |
| `taskPoolSize` | Immediately — the worker pool resizes in place. |
| `rememberMeDurationDays`, `renewRememberMeKey` | Immediately, for tokens issued afterwards. Rotating the key invalidates every existing remember-me token, which is the point of rotating it. |
| `historyRetentionDays`, `authenticationActivityRetentionDays` | At the **next retention sweep**, within six hours. |

For the three multi-source settings, comparing `databaseSource` with
`effectiveValue` is how a client tells "changed, pending restart" from "in
effect" — the API deliberately does not report a boolean "restart required",
because the two values already say it and a derived flag could disagree with
them.

#### Retention

`historyRetentionDays` and `authenticationActivityRetentionDays` set how long
recorded activity is kept. **`0` means keep forever, and is the default for
both.** Retention deletes audit rows, so an operator who has not chosen a policy
has not asked for their history to be pruned; turning it on is deliberate.

The two windows are independent. Authentication activity and catalog history
answer different questions — "who tried to get in" versus "what happened to the
library" — and a short security-log window rarely means wanting to lose a year of
catalog history with it.

A sweep runs every six hours and deletes rows **strictly older** than the
window, so a window of N days keeps rows exactly N days old. A window of `0`
issues no statement at all rather than a delete that matches nothing. A window
reaching past the epoch — a large window on a fresh install — is also skipped,
because the cutoff would be negative and a negative cutoff is a delete that
means nothing.

Retention is applied when a sweep runs, not when a row is read: shortening a
window does not hide rows that are still stored, and lengthening one does not
bring back rows already deleted. Values are validated as non-negative;
a negative window is rejected with `400 invalid_request` rather than silently
becoming a cutoff in the future that would delete everything.

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

### Named discovery feeds

| Method | Path | Collections |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/{series,media-items}/feeds/new` | both |
| `GET` | `/api/xoboro/v1/{series,media-items}/feeds/updated` | both |
| `GET` | `/api/xoboro/v1/{series,media-items}/feeds/recently-read` | both |
| `GET` | `/api/xoboro/v1/media-items/feeds/on-deck` | media items only |
| `GET` | `/api/xoboro/v1/media-items/feeds/keep-reading` | media items only |

Every one of these is expressible with the parameters the listings already accept,
and that is what they are for. When each client decides for itself what "latest"
sorts by, two clients showing a shelf with the same label show different shelves —
and a report that "latest is wrong" cannot be answered, because nothing ever said
what right was.

So **a feed owns its ordering**, and a `sort` parameter is rejected with
`400 invalid_query` rather than honoured. A caller who wants a different order wants
the general listing; silently ignoring the parameter would be worse than rejecting
it, because the caller would believe they had changed something. `page` and `size`
work as everywhere else.

| Feed | Ordering | Meaning |
| --- | --- | --- |
| `new` | `createdAt` descending | Recently added to the catalog. Sorted by when Xoboro created the row, not the file's own timestamp: a decade-old file copied in today is new to *this* library. |
| `updated` | `updatedAt` descending | Recently changed. Distinct from `new`, because `updatedAt` moves when a volume joins a series that has existed for years — which is the event a reader following it wants. |
| `recently-read` | `lastReadAt` descending | Per-caller by construction; two readers correctly get different answers. |
| `on-deck` | `lastReadAt` descending, next-unread filter | "What do I read next." |
| `keep-reading` | `lastReadAt` descending, started-not-finished filter | "What am I part-way through." |

`on-deck` and `keep-reading` exist on media items only. Both describe what *this
reader* has started, which is a property of an item rather than of a series, and
mounting them under `/series` would answer a question nobody asked with a filter that
means nothing there.

Feeds live under `/feeds/` rather than at the collection root so that a feed name can
never be mistaken for an identifier — otherwise a series legitimately named `new`
would be unreachable.

### Duplicate pages

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/duplicate-pages` | `200 OK` |
| `GET` | `/api/xoboro/v1/duplicate-pages/decided` | `200 OK` |
| `GET` | `/api/xoboro/v1/duplicate-pages/{pageHash}/media-items` | `200 OK` |
| `PUT` | `/api/xoboro/v1/duplicate-pages/{pageHash}` | `200 OK` |

Administrator-only, all four. Duplicate pages expose file names and sizes across
every library, so a caller with a grant on one library must not learn another's
file layout from this surface. A non-administrator receives
`403 duplicate_pages_forbidden`.

`GET /duplicate-pages` lists page hashes that appear in more than one media item
and have no recorded decision. `GET /duplicate-pages/decided` lists the ones that
do, optionally filtered by a repeatable `action` parameter; omitting it returns
every decision rather than none.
`GET /duplicate-pages/{pageHash}/media-items` lists every media item and page
number carrying that hash.

`PUT /duplicate-pages/{pageHash}` records what should happen, with an `action` of
`IGNORE`, `DELETE_MANUAL`, or `DELETE_AUTO`, and an optional `sizeBytes` — optional
because a hash whose pages differ in size has no single size, and the candidate
listing reports `null` for it.

**Xoboro records these decisions and does not perform removal.** Nothing executes
`DELETE_AUTO` or `DELETE_MANUAL`: removing a page means rewriting an archive on
disk, which is destructive, irreversible for the operator's own files, and a
decision that belongs to whoever owns those files rather than to a sweep. The two
delete actions are stored as stated intent, and `deleteCount` is therefore always
`0` — it is the stored value, not a placeholder that will change shape later.

`IGNORE` is the one action with an effect today, and a real one: the candidate list
excludes any hash with a recorded decision, so ignoring a hash removes it from the
list permanently. That is why the delete actions are not rejected outright — the
surface is useful without removal existing.

### External login configuration

| Method | Path | Success |
| --- | --- | --- |
| `GET` | `/api/xoboro/v1/authentication/oauth2` | `200 OK` |

Administrator-only. Reports the OAuth2/OIDC providers a running deployment
resolved — `registrationId` and display `name` — together with the effective
login policy: `accountCreationEnabled`, `oidcEmailVerificationRequired`, and
`accountLinking`.

Read-only by design. Provider registration lives in environment variables so that
client secrets never enter the database, which is the one artifact that gets
backed up, copied elsewhere to debug, and restored onto other hosts. What was
missing was not the ability to change the configuration — anyone who can set
environment variables already can — but the ability to see what the running
process resolved without shell access to the host.

No client id, client secret, or endpoint URI is returned. Publishing those would
turn a configuration display into a credential disclosure.

`accountLinking` decides whether an external identity may sign in as an existing
local account, and is set by `XOBORO_OAUTH2_ACCOUNT_LINKING`:

| Value | Behaviour |
| --- | --- |
| `VERIFIED_EMAIL` | Default. Links only when the provider asserted it verified the email. A plain OAuth2 provider makes no such assertion, so it can never link — only create. |
| `EMAIL` | Links on an email match whatever the provider verified. Komga's behaviour. |
| `NEVER` | Never links; a matching email is refused rather than signed in or duplicated. |

A refused link returns `account_linking_requires_verified_email` or
`account_linking_disabled`. An unrecognised value for the environment variable
fails startup rather than falling back to a default — a misspelled security
setting must not resolve to something that merely looks like what was meant. See
ADR 0093.

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

Every native session outcome is recorded under the source `XoboroSession`:
initial setup, an accepted login, rejected credentials, and a cross-site session
attempt refused by the CSRF check. The source is deliberately distinct from the
Komga-compatible surface's `Password`, `ApiKey`, `RememberMe`, and per-provider
OAuth2 sources — those are different entry points with different CSRF and
transport rules, and one shared name would leave an administrator unable to tell
which door was used.

Two of those rows exist precisely because they are invisible elsewhere. A
**rejected credential** row keeps the submitted email even though no account
matched it, because "someone is trying this address" is the value of a failure
row; `userId` is null for those. A **cross-site rejection** is recorded as a
failure with the CSRF error code, since the response goes to the browser that was
refused and nothing else would tell an administrator it happened.

Recording never affects the outcome: a failure to write an activity row cannot
turn a valid login into a rejected one.

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

Route-specific `*_not_found` and `*_forbidden` codes survive the production
pipeline. `Application.kt` installs a global `StatusPages
status(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)` handler that would
otherwise flatten them to a generic `{"code":"forbidden"}` or
`{"code":"not_found"}`; it is guarded by the `XoboroNativeErrorBodyWritten`
attribute, so a route that already wrote its own code is left alone and the
generic body is only used when no route claimed the status.

This is pinned through the real production module rather than through a route in
isolation, because route-level tests install their own minimal `StatusPages`
without the flattening handler and so cannot catch the defect class:
`XoboroNativeErrorContractApplicationTest` for `*_forbidden` and
`XoboroNativeOpsApplicationTest` for `*_not_found`.

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

**`poster.changed` is emitted for all four owner kinds.** For a collection or
read list it is scoped to the grouping's member series or media items, exactly
as `collection.changed` and `read-list.changed` are, so a subscriber hears about
a cover change only if they can see at least one member.

The single exception is a grouping with **no members**: no member is visible, so
the change is not announced at all. This matches the read paths, where a
grouping with no visible members is not visible either. A client that creates an
empty collection, uploads a cover for it, and waits for an event will wait
forever — it should re-read after its own mutation rather than expect one.

The `ids` payload names the **grouping**, not its members: the client is being
told which cover changed, and the membership is only how the server decided who
may hear it.

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
