import { XoboroApiError, XoboroNetworkError, parseRetryAfter } from './errors.js'

/** Where the native API sits relative to the deployment root. */
const API_SUFFIX = 'api/xoboro/v1'

/** Vite's asset directory. Pinned in `vite.config.js`; the derivation below relies on it. */
const ASSET_DIR = '/assets/'

/**
 * Works out the deployment root, then the API root under it.
 *
 * Xoboro can be served under a context path: `Application.kt` wraps every route
 * in `route(contextPath, routes)` from `runtime.effectiveServerContextPath`, so a
 * deployment at `/xoboro` answers the API at `/xoboro/api/xoboro/v1`. A hardcoded
 * `/api/xoboro/v1` would 404 against every endpoint there.
 *
 * The root is taken from **this bundle's own URL** rather than from
 * `location.pathname`, because the script's location does not move when the user
 * navigates while a path can: the server falls back to `index.html` for unknown
 * paths, so `/xoboro/a/b` would otherwise be read as a root of `/xoboro/a/`.
 * `location.pathname` is only the fallback, for the dev server where the entry is
 * `/src/main.js` and there is no asset directory.
 *
 * The result stays a same-origin path. The cookie transport is `SameSite=Strict`
 * and its mutations require same-origin provenance (an exact `Origin` match or
 * `Sec-Fetch-Site: same-origin`), which the browser supplies for a same-origin
 * request and cannot be forged; an absolute cross-origin base would defeat the
 * CSRF protection and break authentication at the same time.
 *
 * @param {object} [source]
 * @param {string} [source.scriptUrl] this module's URL, absolute in a build.
 * @param {string} [source.pathname] the document path, used only as a fallback.
 * @returns {string} the API root, with no trailing slash.
 */
export function resolveApiBase(source = {}) {
  const { scriptUrl = '', pathname = '/' } = source

  const assetAt = scriptUrl.indexOf(ASSET_DIR)
  if (assetAt >= 0) {
    const root = scriptUrl.slice(0, assetAt)
    // Absolute in a build ("http://host/xoboro"), so keep only the path part: the
    // origin must come from the document, not from wherever the asset was fetched.
    const withoutOrigin = root.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]*/i, '')
    return `${withoutOrigin}/${API_SUFFIX}`
  }

  // Everything up to and including the last slash — the directory the document was
  // served from. A trailing filename ("/xoboro/index.html") is not a directory.
  const directory = pathname.slice(0, pathname.lastIndexOf('/') + 1) || '/'
  return `${directory}${API_SUFFIX}`
}

/**
 * The native API root for this deployment.
 *
 * Computed once at load. It cannot change without the page reloading, and every
 * request goes through this module, so there is nothing to invalidate.
 */
export const API_BASE = resolveApiBase({
  scriptUrl: typeof import.meta.url === 'string' ? import.meta.url : '',
  pathname: globalThis.location?.pathname ?? '/',
})

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
