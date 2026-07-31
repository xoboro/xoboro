import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ApiKeys from '../src/admin/ApiKeys.svelte'

function reply(body, status = 200) {
  const text = body === null ? '' : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

const TOKEN = 'synthetic-plaintext-token-value'

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

/**
 * This is the only screen that ever holds a usable secret.
 *
 * The server returns the plaintext key once and stores a digest, so the key cannot be
 * retrieved again. That makes two properties worth pinning: the value has to be shown
 * (a key nobody can read is a key nobody can use), and it must not survive anywhere the
 * screen could leak it from.
 */
describe('ApiKeys', () => {
  it('shows the plaintext key once and says that is the only time', async () => {
    globalThis.fetch = vi.fn(async (url, init = {}) =>
      init.method === 'POST'
        ? reply({ id: 'k1', comment: 'Synthetic key', token: TOKEN, scopes: [] })
        : reply([]),
    )
    render(ApiKeys)

    await waitFor(() => expect(screen.getByTestId('add-key')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('add-key'))
    await fireEvent.input(screen.getByTestId('key-comment'), {
      target: { value: 'Synthetic key' },
    })
    await fireEvent.submit(document.getElementById('key-form'))

    const shown = await screen.findByTestId('issued-token')
    expect(shown.textContent).toBe(TOKEN)
    // Rendered as text, not into a form control. An <input value=...> is a surface a
    // browser offers to remember, and this value is not ours to have remembered.
    expect(shown.tagName).toBe('CODE')
    expect(screen.getByTestId('shown-once')).toBeInTheDocument()
  })

  it('never writes the plaintext key to browser storage', async () => {
    // Asserted over the whole of both stores rather than against a key name this test
    // would have to guess. The property is "the token is not in there", and a
    // name-specific check would pass if it were written under a different one.
    globalThis.fetch = vi.fn(async (url, init = {}) =>
      init.method === 'POST'
        ? reply({ id: 'k1', comment: 'Synthetic key', token: TOKEN, scopes: [] })
        : reply([]),
    )
    render(ApiKeys)

    await waitFor(() => expect(screen.getByTestId('add-key')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('add-key'))
    await fireEvent.input(screen.getByTestId('key-comment'), {
      target: { value: 'Synthetic key' },
    })
    await fireEvent.submit(document.getElementById('key-form'))
    await screen.findByTestId('issued-token')

    for (const store of [localStorage, sessionStorage]) {
      const contents = Object.keys(store)
        .map((name) => `${name}=${store.getItem(name)}`)
        .join('\n')
      expect(contents).not.toContain(TOKEN)
    }
  })

  it('does not keep the key on screen once the dialog is dismissed', async () => {
    // There is no second viewing. Leaving it rendered behind a closed dialog would make
    // "shown once" untrue in the one direction that matters.
    globalThis.fetch = vi.fn(async (url, init = {}) =>
      init.method === 'POST'
        ? reply({ id: 'k1', comment: 'Synthetic key', token: TOKEN, scopes: [] })
        : reply([]),
    )
    const { container } = render(ApiKeys)

    await waitFor(() => expect(screen.getByTestId('add-key')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('add-key'))
    await fireEvent.input(screen.getByTestId('key-comment'), {
      target: { value: 'Synthetic key' },
    })
    await fireEvent.submit(document.getElementById('key-form'))
    await screen.findByTestId('issued-token')

    // The panel's own close control. `Dialog` also labels its backdrop "close", so a
    // role-and-name query matches two elements — only one of which is the header button
    // a user tabs to, the backdrop being outside the focus trap.
    await fireEvent.click(container.querySelector('.panel .close'))

    await waitFor(() => expect(screen.queryByTestId('issued-token')).toBeNull())
    // The whole document, not just the dialog: the value must not be left anywhere.
    expect(container.innerHTML).not.toContain(TOKEN)
  })

  it('lists an expired key instead of hiding it', async () => {
    // A key that stopped working is exactly the one its owner is looking for. Hiding it
    // leaves them unable to tell which of their keys died.
    const past = Date.now() - 60_000
    globalThis.fetch = vi.fn(async () =>
      reply([{ id: 'k1', comment: 'Expired key', scopes: [], expiresAtMillis: past }]),
    )
    render(ApiKeys)

    await waitFor(() => expect(screen.getByText('Expired key')).toBeInTheDocument())
    expect(screen.getByTestId('delete-key-k1')).toBeInTheDocument()
  })
})
