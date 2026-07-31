import { render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App.svelte'
import { SessionStatus, session } from '../src/lib/session.js'

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

const EMPTY_PAGE = {
  items: [],
  page: 0,
  size: 20,
  totalItems: 0,
  totalPages: 0,
  hasPrevious: false,
  hasNext: false,
}

/**
 * Answers each native path with a canned reply, keyed by the path itself.
 *
 * Anything unmatched gets an empty page. This file tests the session gate, and the
 * screens behind it fetch their own data - listing every one of their requests here
 * would make the gate's tests fail whenever a screen started asking for something
 * else.
 */
function router(routes) {
  return vi.fn(async (url) => {
    for (const [path, response] of Object.entries(routes)) {
      if (url.startsWith(`/api/xoboro/v1${path}`)) return response
    }
    return reply({ body: EMPTY_PAGE })
  })
}

/** The reader shell is what renders once a session exists. */
const signedIn = (container) => container.querySelector('a[href="#/collections"]')
/** Only an administrator is offered the console. */
const consoleLink = (container) => container.querySelector('a[href="#/admin"]')

beforeEach(() => {
  session.set({ status: SessionStatus.UNKNOWN, user: null })
  // jsdom has no EventSource. The hub only starts once authenticated, and the
  // tests that get that far assert on the shell rather than on the stream.
  globalThis.EventSource = class {
    constructor() {
      this.readyState = 0
    }
    addEventListener() {}
    close() {}
  }
})

describe('session gate', () => {
  it('shows the setup form on an unclaimed server', async () => {
    globalThis.fetch = router({
      '/session': reply({ status: 401, body: { code: 'authentication_required', message: 'no' } }),
      '/setup': reply({ body: { claimed: false } }),
    })
    const { container } = render(App)

    await waitFor(() => expect(container.querySelector('#setup-email')).toBeInTheDocument())
    // Setup and sign-in are mutually exclusive: offering both would let someone
    // try to claim a server that already has an administrator.
    expect(container.querySelector('#signin-email')).toBeNull()
  })

  it('shows the sign-in form on a claimed server', async () => {
    globalThis.fetch = router({
      '/session': reply({ status: 401, body: { code: 'authentication_required', message: 'no' } }),
      '/setup': reply({ body: { claimed: true } }),
    })
    const { container } = render(App)

    await waitFor(() => expect(container.querySelector('#signin-email')).toBeInTheDocument())
    expect(container.querySelector('#setup-email')).toBeNull()
  })

  it('does not show a login form before the server has answered', async () => {
    // The cookie is HttpOnly, so the client cannot know whether it is signed in
    // until the server says. Rendering the form during that window would ask
    // people who are already signed in to sign in again on every reload.
    let resolveSession
    globalThis.fetch = vi.fn(() => new Promise((resolve) => (resolveSession = resolve)))
    const { container } = render(App)

    expect(container.querySelector('#signin-email')).toBeNull()
    expect(container.querySelector('#setup-email')).toBeNull()
    expect(screen.getByRole('status')).toBeInTheDocument()

    resolveSession(reply({ body: { user: { id: 'u1', email: 'a@example.invalid', roles: [] } } }))
    await waitFor(() => expect(signedIn(container)).toBeTruthy())
  })

  it('never asks for the setup state once a session exists', async () => {
    // The answer could not change anything on screen, and /setup is
    // unauthenticated - there is no reason to reveal that this client asked.
    const fetchImpl = router({
      '/session': reply({ body: { user: { id: 'u1', email: 'a@example.invalid', roles: [] } } }),
    })
    globalThis.fetch = fetchImpl
    const { container } = render(App)

    await waitFor(() => expect(signedIn(container)).toBeTruthy())
    const asked = fetchImpl.mock.calls.map(([url]) => url)
    expect(asked.some((url) => url.includes('/setup'))).toBe(false)
  })

  it('offers the console to an administrator', async () => {
    globalThis.fetch = router({
      '/session': reply({
        body: { user: { id: 'u1', email: 'admin@example.invalid', roles: ['ADMIN'] } },
      }),
    })
    const { container } = render(App)
    await waitFor(() => expect(consoleLink(container)).toBeTruthy())
  })

  it('does not offer the console to a reader', async () => {
    // Presentation, not protection - the server refuses those routes regardless - but
    // showing a door that only answers 403 is a worse experience than not showing it.
    globalThis.fetch = router({
      '/session': reply({
        body: { user: { id: 'u2', email: 'reader@example.invalid', roles: ['PAGE_STREAMING'] } },
      }),
    })
    const { container } = render(App)
    await waitFor(() => expect(signedIn(container)).toBeTruthy())
    expect(consoleLink(container)).toBeNull()
  })
})
