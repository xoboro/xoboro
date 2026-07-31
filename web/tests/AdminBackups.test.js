import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Backups from '../src/admin/Backups.svelte'
import messages from '../src/lib/messages/ko.js'

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

const BACKUP = {
  id: 'synthetic-backup-1',
  createdAtMillis: 1_735_689_600_000,
  sizeBytes: 5_242_880,
}

/**
 * Deleting a backup destroys the only copy of a database snapshot.
 *
 * It is the one destructive action here whose blast radius is legitimately read from
 * the already-loaded row rather than re-counted: a backup's creation time and size are
 * facts about a file that has already been written and cannot change under the
 * operator. What matters is that both are stated, and that the deletion is gated.
 */
describe('Backups', () => {
  it('states the size and age of the backup being destroyed', async () => {
    globalThis.fetch = vi.fn(async () => reply([BACKUP]))
    render(Backups)

    await waitFor(() => expect(screen.getByTestId('delete-backup-synthetic-backup-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-backup-synthetic-backup-1'))

    const summary = await screen.findByTestId('confirm-summary')
    // 5 MiB, so the megabyte figure has to appear rather than a raw byte count an operator
    // cannot weigh. Matched as a number next to its unit: `toContain('5')` was satisfied by
    // the "2025" in the date, and passed with `sizeBytes: 0` and with the field absent.
    expect(summary.textContent).toMatch(/\b5\.0 MB\b/)
    // The creation time is rendered through the locale, so the year is asserted rather
    // than a formatted string this suite would then be pinning as copy.
    expect(summary.textContent).toContain('2025')
  })

  it('says the size is unknown rather than reporting zero', async () => {
    // A `null` divided by 1 MiB is 0, so an absent size rendered as "0.0 MB" — a measured
    // fact that was never measured, on the confirmation for an irreversible delete.
    globalThis.fetch = vi.fn(async () => reply([{ ...BACKUP, sizeBytes: null }]))
    render(Backups)

    await waitFor(() => expect(screen.getByTestId('delete-backup-synthetic-backup-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-backup-synthetic-backup-1'))

    const summary = await screen.findByTestId('confirm-summary')
    expect(summary.textContent).not.toMatch(/MB/)
    // Read from the catalog rather than written out here: the suite renders in Korean, and a
    // literal would put translated copy in a test source and pin it as an assertion.
    expect(summary.textContent).toContain(messages.admin.backups.sizeUnknown)
  })

  it('gates deletion behind typing the backup identifier', async () => {
    globalThis.fetch = vi.fn(async () => reply([BACKUP]))
    const { container } = render(Backups)

    await waitFor(() => expect(screen.getByTestId('delete-backup-synthetic-backup-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-backup-synthetic-backup-1'))

    const confirm = await screen.findByTestId('typed-confirm')
    expect(confirm).toBeDisabled()

    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: BACKUP.id },
    })
    expect(confirm).not.toBeDisabled()
  })

  it('deletes the backup that was confirmed', async () => {
    // Two backups, so targeting the wrong one is a failure this test can see. A single
    // fixture would pass whatever identifier the request carried.
    const second = { ...BACKUP, id: 'synthetic-backup-2' }
    const fetchImpl = vi.fn(async (url, init = {}) =>
      init.method === 'DELETE' ? reply(null, 204) : reply([BACKUP, second]),
    )
    globalThis.fetch = fetchImpl
    const { container } = render(Backups)

    await waitFor(() => expect(screen.getByTestId('delete-backup-synthetic-backup-2')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-backup-synthetic-backup-2'))
    await screen.findByTestId('typed-confirm')
    await fireEvent.input(container.querySelector('.panel input'), {
      target: { value: second.id },
    })
    await fireEvent.click(screen.getByTestId('typed-confirm'))

    await waitFor(() => {
      const deletes = fetchImpl.mock.calls.filter(([, init]) => init?.method === 'DELETE')
      expect(deletes).toHaveLength(1)
      expect(deletes[0][0]).toContain(second.id)
      expect(deletes[0][0]).not.toContain(BACKUP.id)
    })
  })
})
