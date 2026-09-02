import { get } from 'svelte/store'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SessionStatus,
  isAdministrator,
  loadSession,
  session,
  signIn,
  signOut,
} from '../src/lib/session.js'

function reply({ status = 200, body = null } = {}) {
  const text = body === null ? '' : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

beforeEach(() => {
  session.set({ status: SessionStatus.UNKNOWN, user: null })
})

describe('isAdministrator', () => {
  it('is true only for the ADMIN role', () => {
    expect(isAdministrator({ roles: ['ADMIN'] })).toBe(true)
    expect(isAdministrator({ roles: ['ADMIN', 'FILE_DOWNLOAD'] })).toBe(true)
    expect(isAdministrator({ roles: ['PAGE_STREAMING'] })).toBe(false)
  })

  it('is false for an account with no roles at all', () => {
    // There is no USER role in the domain. A reader holds only the capability
    // roles it was granted and may hold none, so "not an administrator" cannot be
    // written as "holds some other role".
    expect(isAdministrator({ roles: [] })).toBe(false)
    expect(isAdministrator({})).toBe(false)
    expect(isAdministrator(null)).toBe(false)
  })
})

describe('loadSession', () => {
  it('resolves an expired session to ANONYMOUS rather than throwing', async () => {
    // Nobody being signed in is an ordinary state, not an error the shell has to
    // catch at every call site.
    globalThis.fetch = vi.fn().mockResolvedValue(
      reply({ status: 401, body: { code: 'authentication_required', message: 'no' } }),
    )
    await expect(loadSession()).resolves.toBeNull()
    expect(get(session).status).toBe(SessionStatus.ANONYMOUS)
  })

  it('propagates a failure that is not about authentication', async () => {
    // A 502 is not "signed out". Swallowing it would silently show the login form
    // to someone whose session is perfectly valid.
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ status: 502 }))
    await expect(loadSession()).rejects.toMatchObject({ code: 'http_502' })
    expect(get(session).status).toBe(SessionStatus.UNKNOWN)
  })

  it('records the user when the session is live', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(
      reply({ body: { user: { id: 'u1', email: 'a@example.invalid', roles: ['ADMIN'] } } }),
    )
    await loadSession()
    expect(get(session)).toMatchObject({
      status: SessionStatus.AUTHENTICATED,
      user: { id: 'u1' },
    })
  })
})

describe('signIn', () => {
  it('asks for the cookie transport and keeps no token', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      reply({ body: { user: { id: 'u1', email: 'a@example.invalid', roles: [] } } }),
    )
    globalThis.fetch = fetchImpl
    const user = await signIn('a@example.invalid', 'synthetic-password', true)

    const [, init] = fetchImpl.mock.calls[0]
    expect(JSON.parse(init.body).transport).toBe('COOKIE')
    expect(JSON.parse(init.body).rememberMe).toBe(true)
    // The response for this transport carries no token, and nothing in the store
    // has anywhere to put one.
    expect(user.accessToken).toBeUndefined()
    expect(get(session).user.accessToken).toBeUndefined()
  })
})

describe('signOut', () => {
  it('clears local state even when the server call fails', async () => {
    // Staying "signed in" because the sign-out request errored leaves someone who
    // asked to leave still looking at their library.
    session.set({ status: SessionStatus.AUTHENTICATED, user: { id: 'u1', roles: [] } })
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('failed to fetch'))

    await expect(signOut()).rejects.toBeTruthy()
    expect(get(session)).toEqual({ status: SessionStatus.ANONYMOUS, user: null })
  })
})
