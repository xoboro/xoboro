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

  it('states the blast radius from a count read when the dialog opens', async () => {
    // The two replies differ deliberately. With one `mockResolvedValue` for every call
    // this assertion could not tell a re-read from the screen's stale snapshot and
    // passed either way - which is how it came to describe behaviour the code did not
    // have. 7 is what the screen was showing; 2 is what the server holds now.
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(reply({ pending: 7, running: 0, dead: 0 }))
      .mockResolvedValue(reply({ pending: 2, running: 0, dead: 0 }))
    globalThis.fetch = fetchImpl
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-unclaimed'))

    const summary = await screen.findByTestId('discard-summary')
    expect(summary.textContent).toContain('2')
    expect(summary.textContent).not.toContain('7')
  })

  it('holds the stated count still while the confirmation is open', async () => {
    // The dialog names a number and the operator is deciding about that number. A poll
    // that moved it underneath them would make what they agreed to different from what
    // they read.
    vi.useFakeTimers()
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(reply({ pending: 4, running: 0, dead: 0 }))
      .mockResolvedValueOnce(reply({ pending: 4, running: 0, dead: 0 }))
      .mockResolvedValue(reply({ pending: 99, running: 0, dead: 0 }))
    globalThis.fetch = fetchImpl
    render(Tasks)

    await vi.waitFor(() => expect(screen.getByTestId('discard-unclaimed')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-unclaimed'))
    const summary = await vi.waitFor(() => screen.getByTestId('discard-summary'))
    expect(summary.textContent).toContain('4')

    const callsWhenOpened = fetchImpl.mock.calls.length
    await vi.advanceTimersByTimeAsync(15_000)

    expect(fetchImpl.mock.calls.length).toBe(callsWhenOpened)
    expect(screen.getByTestId('discard-summary').textContent).toContain('4')
    vi.useRealTimers()
  })

  it('reports how many were discarded', async () => {
    // The count is the answer someone clearing a backlog came for, which is why the
    // route returns 200 with a body rather than 204.
    //
    // Dispatched on the request rather than on call order. An ordered mock broke the
    // moment opening the dialog added a queue read, because the reply meant for the
    // discard was handed to that read instead - order is not a property of the code
    // under test, so a test should not depend on it.
    let cleared = false
    const fetchImpl = vi.fn(async (url, init = {}) => {
      if (init.method === 'DELETE') {
        cleared = true
        return reply({ cleared: 7 })
      }
      return reply({ pending: cleared ? 0 : 7, running: 0, dead: 0 })
    })
    globalThis.fetch = fetchImpl
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-unclaimed')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-unclaimed'))
    await fireEvent.click(await screen.findByTestId('confirm-discard'))

    await waitFor(() => {
      const status = screen.getAllByRole('status').map((node) => node.textContent).join(' ')
      expect(status).toContain('7')
    })

    // Found by method rather than by call index: opening the dialog re-reads the queue,
    // so the discard is no longer the second request and an index would silently start
    // asserting about the wrong call.
    const discards = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'DELETE')
    expect(discards).toHaveLength(1)
    expect(discards[0][0]).toContain('/tasks/unclaimed')
  })

  it('targets the dead route when that is the action chosen', async () => {
    // Mixing the two up would discard live queued work while the operator believed
    // they were clearing failures.
    let cleared = false
    const fetchImpl = vi.fn(async (url, init = {}) => {
      if (init.method === 'DELETE') {
        cleared = true
        return reply({ cleared: 5 })
      }
      return reply({ pending: 0, running: 0, dead: cleared ? 0 : 5 })
    })
    globalThis.fetch = fetchImpl
    render(Tasks)

    await waitFor(() => expect(screen.getByTestId('discard-dead')).not.toBeDisabled())
    await fireEvent.click(screen.getByTestId('discard-dead'))
    await fireEvent.click(await screen.findByTestId('confirm-discard'))

    await waitFor(() => {
      const discards = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'DELETE')
      expect(discards).toHaveLength(1)
      expect(discards[0][0]).toContain('/tasks/dead')
    })
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
