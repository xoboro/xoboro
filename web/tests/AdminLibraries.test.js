import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Libraries from '../src/admin/Libraries.svelte'

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

const AVAILABLE = {
  id: 'lib-1',
  name: 'Synthetic Library',
  unavailable: false,
  unavailableSinceMillis: null,
}
const UNAVAILABLE = {
  id: 'lib-2',
  name: 'Detached Library',
  unavailable: true,
  unavailableSinceMillis: 1_700_000_000_000,
}

/** Answers by path so a test does not depend on call ordering. */
function routes(handlers) {
  return vi.fn(async (url, init = {}) => {
    for (const [match, handler] of handlers) {
      if (url.includes(match)) {
        return typeof handler === 'function' ? handler(url, init) : handler
      }
    }
    throw new Error(`unexpected request: ${init.method ?? 'GET'} ${url}`)
  })
}

describe('Libraries', () => {
  it('shows the unreachable state as a word, not only a colour', async () => {
    // This state decides whether deleting is even allowed, so it cannot be carried by
    // a red dot alone.
    globalThis.fetch = routes([['/libraries', reply([AVAILABLE, UNAVAILABLE])]])
    render(Libraries)

    await waitFor(() => expect(screen.getByText('Detached Library')).toBeInTheDocument())
    const row = screen.getByText('Detached Library').closest('tr')
    expect(row.textContent).toMatch(/Unreachable|접근 불가/)
  })

  it('gates deletion behind typing the library name', async () => {
    // A checkbox is not friction, it is a reflex. This destroys catalog rows.
    globalThis.fetch = routes([['/libraries', reply([AVAILABLE])]])
    const { container } = render(Libraries)

    await waitFor(() => expect(screen.getByTestId('delete-lib-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-lib-1'))

    const confirm = await screen.findByTestId('typed-confirm')
    expect(confirm).toBeDisabled()

    const field = container.querySelector('.panel input')
    await fireEvent.input(field, { target: { value: 'synthetic library' } })
    // Compared exactly: a case-insensitive match would be easier to satisfy by
    // accident, which is the one property the field exists to prevent.
    expect(confirm).toBeDisabled()

    await fireEvent.input(field, { target: { value: 'Synthetic Library' } })
    expect(confirm).not.toBeDisabled()
  })

  it('states the trash blast radius using server counts', async () => {
    globalThis.fetch = routes([
      ['/series?', reply({ items: [], totalItems: 412 })],
      ['/media-items?', reply({ items: [], totalItems: 1_004 })],
      ['/libraries', reply([AVAILABLE])],
    ])
    render(Libraries)

    await waitFor(() => expect(screen.getByTestId('empty-trash-lib-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('empty-trash-lib-1'))

    const dialog = await screen.findByRole('dialog')
    // Both numbers come from the server's totalItems, not from a page the screen
    // happened to have loaded.
    expect(dialog.textContent).toContain('412')
    expect(dialog.textContent).toContain('1004')
  })

  it('offers a re-check before any override when deletion is refused', async () => {
    // The server refuses with library_unavailable so a catalog is not destroyed
    // because a mount went missing. The next step is to find out whether the mount is
    // back — not to try harder.
    globalThis.fetch = routes([
      [
        '/libraries/lib-2',
        (url, init) =>
          init.method === 'DELETE'
            ? reply({ code: 'library_unavailable', message: 'unavailable' }, 409)
            : reply(UNAVAILABLE),
      ],
      ['/libraries', reply([UNAVAILABLE])],
    ])
    const { container } = render(Libraries)

    await waitFor(() => expect(screen.getByTestId('delete-lib-2')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-lib-2'))
    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: 'Detached Library' },
    })
    await fireEvent.click(screen.getByTestId('typed-confirm'))

    await waitFor(() => expect(screen.getByTestId('recheck')).toBeInTheDocument())
    // The override exists but is not the primary action, and nothing is pre-selected.
    expect(screen.getByTestId('force-delete')).toBeInTheDocument()
    expect(screen.getByTestId('recheck').classList.contains('primary')).toBe(true)
    expect(screen.getByTestId('force-delete').classList.contains('primary')).toBe(false)
  })

  it('does not send force on an ordinary delete', async () => {
    // The word "force" must not appear in a request that is not forcing anything —
    // that is the log line someone later misreads.
    const fetchImpl = routes([
      ['/libraries/lib-1', reply(null, 204)],
      ['/libraries', reply([AVAILABLE])],
    ])
    globalThis.fetch = fetchImpl
    const { container } = render(Libraries)

    await waitFor(() => expect(screen.getByTestId('delete-lib-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-lib-1'))
    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: 'Synthetic Library' },
    })
    await fireEvent.click(screen.getByTestId('typed-confirm'))

    await waitFor(() => {
      const deletes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'DELETE')
      expect(deletes).toHaveLength(1)
      expect(deletes[0][0]).not.toContain('force')
    })
  })

  it('sends force only after the override is chosen, and re-asks for the name', async () => {
    const fetchImpl = routes([
      [
        '/libraries/lib-2/availability',
        reply(UNAVAILABLE),
      ],
      [
        '/libraries/lib-2',
        (url, init) => {
          if (init.method !== 'DELETE') return reply(UNAVAILABLE)
          return url.includes('force=true')
            ? reply(null, 204)
            : reply({ code: 'library_unavailable', message: 'unavailable' }, 409)
        },
      ],
      ['/libraries', reply([UNAVAILABLE])],
    ])
    globalThis.fetch = fetchImpl
    const { container } = render(Libraries)

    await waitFor(() => expect(screen.getByTestId('delete-lib-2')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-lib-2'))
    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: 'Detached Library' },
    })
    await fireEvent.click(screen.getByTestId('typed-confirm'))

    await waitFor(() => expect(screen.getByTestId('recheck')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('force-delete'))

    // A second typed confirmation, with the override stated. Forcing is a separate
    // decision, so it does not inherit the agreement given to the ordinary delete.
    const warning = await screen.findByTestId('force-warning')
    expect(warning).toBeInTheDocument()
    const forceConfirm = screen.getByTestId('typed-confirm')
    expect(forceConfirm).toBeDisabled()

    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: 'Detached Library' },
    })
    await fireEvent.click(forceConfirm)

    await waitFor(() => {
      const forced = fetchImpl.mock.calls.filter(
        ([url, init]) => init?.method === 'DELETE' && url.includes('force=true'),
      )
      expect(forced).toHaveLength(1)
    })
  })

  it('clears the refusal when the storage is reachable again', async () => {
    const fetchImpl = routes([
      ['/libraries/lib-2/availability', reply({ ...UNAVAILABLE, unavailable: false })],
      [
        '/libraries/lib-2',
        (url, init) =>
          init.method === 'DELETE'
            ? reply({ code: 'library_unavailable', message: 'unavailable' }, 409)
            : reply(UNAVAILABLE),
      ],
      ['/libraries', reply([UNAVAILABLE])],
    ])
    globalThis.fetch = fetchImpl
    const { container } = render(Libraries)

    await waitFor(() => expect(screen.getByTestId('delete-lib-2')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-lib-2'))
    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: 'Detached Library' },
    })
    await fireEvent.click(screen.getByTestId('typed-confirm'))

    await waitFor(() => expect(screen.getByTestId('recheck')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('recheck'))

    // No force offered any more: a successful check is what makes an ordinary delete
    // stop being refused.
    await waitFor(() => expect(screen.queryByTestId('force-delete')).toBeNull())
  })

  it('reports a maintenance trigger as accepted, not finished', async () => {
    // These enqueue durable work and answer 202. Claiming completion would be a claim
    // the server never made.
    const fetchImpl = routes([
      ['/libraries/lib-1/scan', reply(null, 202)],
      ['/libraries', reply([AVAILABLE])],
    ])
    globalThis.fetch = fetchImpl
    render(Libraries)

    await waitFor(() => expect(screen.getByText('Synthetic Library')).toBeInTheDocument())
    await fireEvent.click(screen.getByText(/^Scan$|^스캔$/))

    await waitFor(() => {
      const status = screen.getAllByRole('status').map((node) => node.textContent).join(' ')
      expect(status).toMatch(/accepted|접수/)
    })
  })
})
