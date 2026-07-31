import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SignIn from '../src/routes/SignIn.svelte'
import { SessionStatus, session } from '../src/lib/session.js'

function reply({ status, body, headers = {} }) {
  const text = body === null || body === undefined ? '' : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers[name] ?? null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

async function fillAndSubmit(container) {
  await fireEvent.input(container.querySelector('#signin-email'), {
    target: { value: 'a@example.invalid' },
  })
  await fireEvent.input(container.querySelector('#signin-password'), {
    target: { value: 'synthetic-password' },
  })
  await fireEvent.submit(container.querySelector('form'))
}

beforeEach(() => {
  session.set({ status: SessionStatus.UNKNOWN, user: null })
})

describe('SignIn', () => {
  it('signs in and records the session', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(
      reply({ status: 200, body: { user: { id: 'u1', email: 'a@example.invalid', roles: [] } } }),
    )
    const { container } = render(SignIn)
    await fillAndSubmit(container)

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledTimes(1))
    const [, init] = globalThis.fetch.mock.calls[0]
    expect(JSON.parse(init.body).transport).toBe('COOKIE')
  })

  it('blocks submission while the server says to wait', async () => {
    // Rejected attempts consume the same ten-per-minute budget as accepted ones,
    // so a form that lets someone keep hammering a throttled endpoint only spends
    // the rest of their budget for them.
    globalThis.fetch = vi.fn().mockResolvedValue(
      reply({
        status: 429,
        body: { code: 'rate_limit_exceeded', message: 'slow down' },
        headers: { 'Retry-After': '30' },
      }),
    )
    const { container } = render(SignIn)
    await fillAndSubmit(container)

    const submit = screen.getByRole('button')
    await waitFor(() => expect(submit).toBeDisabled())

    // A second submit while blocked must not reach the network at all.
    await fireEvent.submit(container.querySelector('form'))
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
  })

  it('marks the password field when the credentials are refused', async () => {
    // Rendered on the responsible input rather than as a detached toast, so the
    // person can see which of the two fields to correct.
    globalThis.fetch = vi.fn().mockResolvedValue(
      reply({ status: 401, body: { code: 'invalid_credentials', message: 'no' } }),
    )
    const { container } = render(SignIn)
    await fillAndSubmit(container)

    const password = container.querySelector('#signin-password')
    await waitFor(() => expect(password).toHaveAttribute('aria-invalid', 'true'))
    expect(password).toHaveAttribute('aria-describedby', 'signin-error')
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('stays on the form when a rejected login answers 401', async () => {
    // A rejected login is a 401, exactly like an expired session. If that were
    // treated as a lost session, the form would tear itself down mid-attempt.
    globalThis.fetch = vi.fn().mockResolvedValue(
      reply({ status: 401, body: { code: 'invalid_credentials', message: 'no' } }),
    )
    const { container } = render(SignIn)
    await fillAndSubmit(container)

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(container.querySelector('form')).toBeInTheDocument()
  })
})
