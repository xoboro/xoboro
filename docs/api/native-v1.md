# Xoboro native API v1

The native API is the supported boundary for Xoboro web and mobile clients. It
is rooted at `/api/xoboro/v1`, uses JSON request and response bodies, and does
not reproduce Komga DTOs or endpoint shapes.

This document covers authentication, catalog discovery, and read progress
mutation. Administration mutations, media delivery, and OpenAPI contracts will
be added as their native routes are released.

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
