import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Tasks from '../src/admin/Tasks.svelte'

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

beforeEach(() => {
  vi.useRealTimers()
})

describe('Tasks', () => {
  it('keeps the two discard actions separate', async () => {
    // They discard different things: unclaimed work no worker has taken, versus rows
    // that permanently failed. One button labelled "clear the queue" would throw away
    // whichever the operator did not mean, and neither is recoverable.
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ pending: 3, running: 1, dead: 2 }))
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).toBeInTheDocument())
    expect(screen.getByTestId('discard-dead')).toBeInTheDocument()
  })

  it('disables a discard that has nothing to discard', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ pending: 0, running: 0, dead: 4 }))
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).toBeDisabled())
    expect(screen.getByTestId('discard-dead')).not.toBeDisabled()
  })

  it('states the blast radius from the server count before confirming', async () => {
    // A confirmation that cannot say how much it destroys is not a confirmation.
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ pending: 7, running: 0, dead: 0 }))
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-unclaimed'))

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('7')
  })

  it('reports how many were discarded', async () => {
    // The count is the answer someone clearing a backlog came for, which is why the
    // route returns 200 with a body rather than 204.
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(reply({ pending: 7, running: 0, dead: 0 }))
      .mockResolvedValueOnce(reply({ cleared: 7 }))
      .mockResolvedValue(reply({ pending: 0, running: 0, dead: 0 }))
    globalThis.fetch = fetchImpl
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-unclaimed'))
    await fireEvent.click(await screen.findByTestId('confirm-discard'))

    await waitFor(() => {
      const status = screen.getAllByRole('status').map((node) => node.textContent).join(' ')
      expect(status).toContain('7')
    })

    const [, init] = fetchImpl.mock.calls[1]
    expect(init.method).toBe('DELETE')
    expect(fetchImpl.mock.calls[1][0]).toContain('/tasks/unclaimed')
  })

  it('targets the dead route when that is the action chosen', async () => {
    // Mixing the two up would discard live queued work while the operator believed
    // they were clearing failures.
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(reply({ pending: 0, running: 0, dead: 5 }))
      .mockResolvedValueOnce(reply({ cleared: 5 }))
      .mockResolvedValue(reply({ pending: 0, running: 0, dead: 0 }))
    globalThis.fetch = fetchImpl
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-dead')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-dead'))
    await fireEvent.click(await screen.findByTestId('confirm-discard'))

    await waitFor(() => expect(fetchImpl.mock.calls[1][0]).toContain('/tasks/dead'))
  })

  it('marks the confirmation as destructive on the dialog itself', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ pending: 2, running: 0, dead: 0 }))
    const { container } = render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-unclaimed'))

    await screen.findByRole('dialog')
    expect(container.querySelector('.panel').classList.contains('danger')).toBe(true)
  })
})
