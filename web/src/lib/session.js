import { writable } from 'svelte/store'
import { onUnauthenticated, request } from './http.js'

/**
 * Session state for the whole application.
 *
 * The `COOKIE` transport is used, so the token is `HttpOnly` and **never visible
 * to JavaScript**. There is deliberately no `hasCredentials()`: the client cannot
 * inspect a cookie it is not allowed to read, so authentication state is only
 * ever what the server last said. `UNKNOWN` is therefore a real state the shell
 * has to render, not a placeholder to skip past.
 *
 * The base this UI grew from stored `btoa(user + ':' + pass)` in `localStorage` —
 * the reusable password, readable by any script on the origin, for the lifetime
 * of the browser profile. This module exists so that cannot come back.
 */
export const SessionStatus = Object.freeze({
  /** Not asked yet. Render a neutral shell, not the login form. */
  UNKNOWN: 'UNKNOWN',
  ANONYMOUS: 'ANONYMOUS',
  AUTHENTICATED: 'AUTHENTICATED',
})

/** @type {import('svelte/store').Writable<{status: string, user: object|null}>} */
export const session = writable({ status: SessionStatus.UNKNOWN, user: null })

/**
 * The route the user was trying to reach when the session turned out to be gone.
 *
 * Kept so that an expired session does not silently dump someone on the home
 * screen after they sign back in.
 */
export const intendedRoute = writable(null)

function authenticated(user) {
  session.set({ status: SessionStatus.AUTHENTICATED, user })
  return user
}

function anonymous() {
  session.set({ status: SessionStatus.ANONYMOUS, user: null })
}

/**
 * Whether a user holds the administrator role.
 *
 * `roles` carries `UserRole` names. There is no `USER` role — an ordinary reader
 * holds only the capability roles it was granted, and may hold none at all, so a
 * "not admin" check must not be written as "has some other role".
 *
 * @param {{roles?: string[]}|null} user
 */
export function isAdministrator(user) {
  return Boolean(user?.roles?.includes('ADMIN'))
}

/**
 * Reads the current session from the server.
 *
 * This is the only way the client learns it is signed in. A missing or expired
 * session answers `401 authentication_required`, which resolves to `ANONYMOUS`
 * rather than throwing, because "nobody is signed in" is an ordinary state.
 */
export async function loadSession() {
  try {
    const body = await request('/session')
    return authenticated(body.user)
  } catch (error) {
    if (error.code === 'authentication_required') {
      anonymous()
      return null
    }
    throw error
  }
}

/**
 * Signs in over the cookie transport.
 *
 * The response for this transport carries no token — the server sets the
 * `HttpOnly` cookie and deliberately omits it from the body.
 *
 * @param {string} email
 * @param {string} password
 */
export async function signIn(email, password) {
  const body = await request('/session', {
    method: 'POST',
    body: { email, password, transport: 'COOKIE' },
  })
  return authenticated(body.user)
}

/** Revokes the presented session server-side and clears local state. */
export async function signOut() {
  try {
    await request('/session', { method: 'DELETE' })
  } finally {
    // Local state is cleared even if the call failed. The alternative — staying
    // "signed in" because the sign-out request errored — leaves someone who
    // asked to leave still looking at their library.
    anonymous()
  }
}

/** Whether the server has an administrator yet. Unauthenticated by design. */
export async function readSetupState() {
  return request('/setup')
}

/**
 * Claims an unconfigured server, creating the first administrator.
 *
 * Answers `409 server_already_claimed` if setup already happened, which the
 * caller should treat as "go to login" rather than as an error to display.
 *
 * @param {string} email
 * @param {string} password
 */
export async function claimServer(email, password) {
  const body = await request('/setup', {
    method: 'POST',
    body: { email, password, transport: 'COOKIE' },
  })
  return authenticated(body.user)
}

/**
 * Wires the transport's lost-session signal into this store.
 *
 * Called once at start-up. Any request that fails with
 * `authentication_required` flips the whole application to `ANONYMOUS`, so a
 * background poll noticing an expired session is enough to drop the shell to the
 * login gate without every screen checking for itself.
 */
export function watchForLostSession() {
  return onUnauthenticated(anonymous)
}
