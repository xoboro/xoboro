import { describe, expect, it, vi } from 'vitest'
import { XoboroApiError, XoboroNetworkError } from '../src/lib/errors.js'
import {
  API_BASE,
  buildQuery,
  onUnauthenticated,
  request,
  resolveApiBase,
  resourceUrl,
} from '../src/lib/http.js'

function reply({ status = 200, body = null, headers = {} } = {}) {
  const text = body === null ? '' : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers[name] ?? null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

describe('resolveApiBase', () => {
  it('serves the API from the deployment root when there is no context path', () => {
    expect(resolveApiBase({ scriptUrl: 'http://host/assets/index-abc.js' }))
      .toBe('/api/xoboro/v1')
  })

  it('follows the server context path', () => {
    // Application.kt wraps every route in route(contextPath, routes), so a
    // deployment at /xoboro answers the API at /xoboro/api/xoboro/v1. A hardcoded
    // root would 404 against every endpoint there.
    expect(resolveApiBase({ scriptUrl: 'http://host/xoboro/assets/index-abc.js' }))
      .toBe('/xoboro/api/xoboro/v1')
    expect(resolveApiBase({ scriptUrl: 'http://host/a/b/assets/index-abc.js' }))
      .toBe('/a/b/api/xoboro/v1')
  })

  it('keeps only the path, never the origin the asset came from', () => {
    // The origin must come from the document. Carrying it over from the asset URL
    // would produce an absolute base, which defeats the SameSite=Strict cookie
    // and the same-origin provenance the mutations require.
    const base = resolveApiBase({ scriptUrl: 'https://cdn.example/xoboro/assets/i.js' })
    expect(base).toBe('/xoboro/api/xoboro/v1')
    expect(base).not.toContain('cdn.example')
  })

  it('falls back to the document directory when there is no asset URL', () => {
    // The dev server serves /src/main.js, so there is no asset directory to cut.
    expect(resolveApiBase({ pathname: '/' })).toBe('/api/xoboro/v1')
    expect(resolveApiBase({ pathname: '/xoboro/' })).toBe('/xoboro/api/xoboro/v1')
  })

  it('does not mistake a filename for a directory', () => {
    expect(resolveApiBase({ pathname: '/xoboro/index.html' })).toBe('/xoboro/api/xoboro/v1')
  })

  it('prefers the asset URL over the path, because the path moves and it does not', () => {
    // The server falls back to index.html for an unknown path, so /xoboro/a/b can
    // legitimately be the current location. Deriving from it would give a root of
    // /xoboro/a/ and every request would 404.
    expect(resolveApiBase({
      scriptUrl: 'http://host/xoboro/assets/index-abc.js',
      pathname: '/xoboro/a/b',
    })).toBe('/xoboro/api/xoboro/v1')
  })
})

describe('API_BASE', () => {
  it('is relative so requests stay same-origin', () => {
    // The cookie transport is SameSite=Strict and its mutations require
    // same-origin provenance. An absolute base would break authentication and
    // defeat the CSRF protection at the same time.
    expect(API_BASE.startsWith('/')).toBe(true)
    expect(API_BASE.startsWith('//')).toBe(false)
    expect(API_BASE).not.toMatch(/^[a-z][a-z0-9+.-]*:/i)
  })

  it('refuses a path that is not local to the API root', () => {
    // Checked on the argument, because prefixing API_BASE always yields a
    // same-origin string — validating the result would be a guard that can never
    // fire. What this catches is a full or protocol-relative URL arriving from
    // server data and being silently mangled into a path that fetches the wrong
    // thing.
    expect(resourceUrl('/media-items/1/pages/1')).toBe('/api/xoboro/v1/media-items/1/pages/1')
    expect(() => resourceUrl('//evil.example/x')).toThrow(/relative to the API root/)
    expect(() => resourceUrl('https://evil.example/x')).toThrow(/relative to the API root/)
    expect(() => resourceUrl('/https://evil.example/x')).toThrow(/scheme/)
    expect(() => resourceUrl('media-items/1')).toThrow(/relative to the API root/)
  })

  it('applies the same check to requests', async () => {
    await expect(request('https://evil.example/session')).rejects.toThrow(/relative/)
  })
})

describe('buildQuery', () => {
  it('repeats a key once per value so array filters survive', () => {
    // The native listings take repeated libraryId, genre and tag filters. Joining
    // an array into one comma-separated value would silently filter by a single
    // identifier that does not exist.
    expect(buildQuery({ libraryId: ['a', 'b'] })).toBe('?libraryId=a&libraryId=b')
  })

  it('keeps false and zero, drops null and undefined', () => {
    // trashed=false and page=0 both mean something. Treating them as absent would
    // silently change which rows a screen shows.
    expect(buildQuery({ trashed: false, page: 0 })).toBe('?trashed=false&page=0')
    expect(buildQuery({ query: null, seriesId: undefined })).toBe('')
  })

  it('returns an empty string rather than a bare question mark', () => {
    expect(buildQuery()).toBe('')
    expect(buildQuery({})).toBe('')
  })
})

describe('request', () => {
  it('sends the session cookie and no Authorization header', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply({ body: { claimed: true } }))
    await request('/setup', { fetchImpl })

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/xoboro/v1/setup')
    expect(init.credentials).toBe('same-origin')
    // The token is HttpOnly and never in JavaScript, so there is nothing to put
    // in an Authorization header even if a call site wanted to.
    expect(init.headers.Authorization).toBeUndefined()
  })

  it('returns null for 204 and for an empty body', async () => {
    const noContent = vi.fn().mockResolvedValue(reply({ status: 204 }))
    expect(await request('/session', { method: 'DELETE', fetchImpl: noContent })).toBeNull()

    const empty = vi.fn().mockResolvedValue(reply({ status: 200 }))
    expect(await request('/session', { fetchImpl: empty })).toBeNull()
  })

  it('turns the error envelope into a XoboroApiError carrying the code', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      reply({ status: 409, body: { code: 'library_unavailable', message: 'unavailable' } }),
    )
    await expect(request('/libraries/x', { method: 'DELETE', fetchImpl }))
      .rejects.toMatchObject({ code: 'library_unavailable', status: 409 })
  })

  it('preserves unknown error envelope fields as details', async () => {
    const progress = {
      page: 8,
      completed: false,
      readAtMillis: 500,
      updatedAtMillis: 510,
    }
    const fetchImpl = vi.fn().mockResolvedValue(
      reply({
        status: 409,
        body: {
          code: 'stale_progress',
          message: 'stale',
          progress,
          requestId: 'synthetic-request',
        },
      }),
    )

    await expect(request('/media-items/m1/progress', { method: 'PUT', fetchImpl }))
      .rejects.toMatchObject({ details: { progress, requestId: 'synthetic-request' } })
  })

  it('passes keepalive to fetch without changing same-origin credentials', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply({ status: 204 }))

    await request('/media-items/m1/progress', { method: 'PUT', keepalive: true, fetchImpl })

    expect(fetchImpl).toHaveBeenCalledWith(
      '/api/xoboro/v1/media-items/m1/progress',
      expect.objectContaining({ keepalive: true, credentials: 'same-origin' }),
    )
  })

  it('parses Retry-After onto the error', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      reply({
        status: 429,
        body: { code: 'rate_limit_exceeded', message: 'slow down' },
        headers: { 'Retry-After': '17' },
      }),
    )
    await expect(request('/session', { method: 'POST', fetchImpl }))
      .rejects.toMatchObject({ retryAfterSeconds: 17 })
  })

  it('synthesises a code when the body is not the native envelope', async () => {
    // A reverse proxy or the dev server can answer with HTML. That is a real
    // failure, so it must not be swallowed, but it carries no code to branch on.
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      headers: { get: () => null },
      text: async () => '<html>bad gateway</html>',
      json: async () => {
        throw new SyntaxError('not json')
      },
    })
    const caught = await request('/tasks', { fetchImpl }).catch((error) => error)
    expect(caught).toBeInstanceOf(XoboroApiError)
    expect(caught.code).toBe('http_502')
  })

  it('reports a transport failure separately from a rejection', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError('failed to fetch'))
    await expect(request('/session', { fetchImpl })).rejects.toBeInstanceOf(XoboroNetworkError)
  })

  it('lets an abort through instead of reporting it as offline', async () => {
    // A screen that navigated away aborts its own request. Reporting that as
    // "the server could not be reached" would put a false error on screen.
    const abort = Object.assign(new Error('aborted'), { name: 'AbortError' })
    const fetchImpl = vi.fn().mockRejectedValue(abort)
    await expect(request('/series', { fetchImpl })).rejects.toBe(abort)
  })
})

describe('lost session signal', () => {
  it('fires only for authentication_required', async () => {
    const handler = vi.fn()
    const stop = onUnauthenticated(handler)
    try {
      const expired = vi.fn().mockResolvedValue(
        reply({ status: 401, body: { code: 'authentication_required', message: 'no' } }),
      )
      await request('/session', { fetchImpl: expired }).catch(() => {})
      expect(handler).toHaveBeenCalledTimes(1)

      // A rejected login is also a 401. Treating every 401 as a lost session
      // would bounce someone out of the login form they are standing in.
      const rejected = vi.fn().mockResolvedValue(
        reply({ status: 401, body: { code: 'invalid_credentials', message: 'no' } }),
      )
      await request('/session', { method: 'POST', fetchImpl: rejected }).catch(() => {})
      expect(handler).toHaveBeenCalledTimes(1)
    } finally {
      stop()
    }
  })
})
