import { XoboroApiError, XoboroNetworkError, parseRetryAfter } from './errors.js'

/**
 * The native API root.
 *
 * Deliberately relative. The cookie transport is `SameSite=Strict` and its
 * mutations require same-origin provenance (an exact `Origin` match or
 * `Sec-Fetch-Site: same-origin`), which the browser supplies for a same-origin
 * request and cannot be forged by the client. Pointing this at an absolute
 * cross-origin URL would defeat the CSRF protection and break authentication
 * outright, so `assertRelative` refuses one.
 */
export const API_BASE = '/api/xoboro/v1'

const unauthenticatedHandlers = new Set()

/**
 * Registers a handler for "the session is gone".
 *
 * Only the `authentication_required` code triggers it — not every `401`.
 * A rejected login is also a `401`, and treating that as a lost session would
 * bounce the user out of the login form they are standing in.
 *
 * @param {() => void} handler
 * @returns {() => void} unregister
 */
export function onUnauthenticated(handler) {
  unauthenticatedHandlers.add(handler)
  return () => unauthenticatedHandlers.delete(handler)
}

/**
 * Rejects anything that is not a plain path under {@link API_BASE}.
 *
 * Checked on the **argument**, not on the concatenated URL: prefixing `API_BASE`
 * always produces a same-origin string, so validating the result would be a guard
 * that can never fire. The risk this actually covers is a caller passing through
 * a full URL or a protocol-relative one — say, a link that arrived in server data
 * — which would then be silently mangled into a same-origin path that requests
 * the wrong thing.
 */
function assertLocalPath(path) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) {
    throw new Error(`native API paths must be relative to the API root, got: ${path}`)
  }
  if (/^\/+[a-z][a-z0-9+.-]*:/i.test(path)) {
    throw new Error(`native API paths must not carry a scheme, got: ${path}`)
  }
}

/**
 * Builds a query string, repeating a key once per value for array parameters.
 *
 * The native listings take repeated `libraryId`, `genre`, `tag`, `publisher` and
 * `language` filters, so an array must not be joined into one comma value.
 * `null` and `undefined` are dropped; `false` and `0` are kept, because both are
 * meaningful (`trashed=false`, `page=0`).
 *
 * @param {Record<string, unknown>} query
 */
export function buildQuery(query = {}) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined) continue
    const values = Array.isArray(value) ? value : [value]
    for (const single of values) {
      if (single === null || single === undefined) continue
      search.append(key, String(single))
    }
  }
  const rendered = search.toString()
  return rendered ? `?${rendered}` : ''
}

async function readError(response, nowMillis) {
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'), nowMillis)
  let code = ''
  let serverMessage = ''
  try {
    const body = await response.json()
    if (body && typeof body.code === 'string') code = body.code
    if (body && typeof body.message === 'string') serverMessage = body.message
  } catch {
    // A non-JSON body means something between the client and the route answered
    // — a reverse proxy, or the dev server. It is a real failure, so it must not
    // be swallowed, but it carries no native code to branch on.
  }
  if (!code) code = `http_${response.status}`
  return new XoboroApiError({ status: response.status, code, serverMessage, retryAfterSeconds })
}

/**
 * Performs one native API request.
 *
 * Every request in the application goes through here. That is what keeps the
 * base path, the credentials mode, the error envelope and the lost-session
 * handling in one place instead of at each call site.
 *
 * @param {string} path path under {@link API_BASE}, starting with `/`.
 * @param {object} [options]
 * @param {string} [options.method]
 * @param {Record<string, unknown>} [options.query]
 * @param {unknown} [options.body] serialised as JSON when present.
 * @param {AbortSignal} [options.signal]
 * @param {typeof fetch} [options.fetchImpl] injected for tests.
 * @param {() => number} [options.now] injected for tests.
 * @returns {Promise<unknown>} parsed JSON, or `null` for an empty body.
 */
export async function request(path, options = {}) {
  const {
    method = 'GET',
    query,
    body,
    signal,
    fetchImpl = globalThis.fetch,
    now = () => Date.now(),
  } = options

  assertLocalPath(path)
  const url = `${API_BASE}${path}${buildQuery(query)}`

  let response
  try {
    response = await fetchImpl(url, {
      method,
      signal,
      // Sends the HttpOnly session cookie. There is no Authorization header on
      // this surface: the token is never in JavaScript, by design.
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  } catch (cause) {
    // An aborted request is the caller's own doing, not a network failure, and
    // must stay distinguishable so a screen does not report "offline" when it
    // navigated away.
    if (cause?.name === 'AbortError') throw cause
    throw new XoboroNetworkError(cause)
  }

  if (!response.ok) {
    const error = await readError(response, now())
    if (error.code === 'authentication_required') {
      for (const handler of unauthenticatedHandlers) handler()
    }
    throw error
  }

  if (response.status === 204) return null
  const text = await response.text()
  if (!text) return null
  return JSON.parse(text)
}

/**
 * Builds a same-origin URL for a resource loaded by the browser rather than by
 * `fetch` — a page image or a thumbnail in an `img` `src`.
 *
 * These carry the session cookie ambiently for the same reason `fetch` does, so
 * they must stay same-origin too.
 *
 * @param {string} path
 */
export function resourceUrl(path) {
  assertLocalPath(path)
  return `${API_BASE}${path}`
}
