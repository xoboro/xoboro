# Xoboro native API v1

The native API is the supported boundary for Xoboro web and mobile clients. It
is rooted at `/api/xoboro/v1`, uses JSON request and response bodies, and does
not reproduce Komga DTOs or endpoint shapes.

This document covers the authentication slice. Catalog, administration, media,
and OpenAPI contracts will be added as their native routes are released.

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
