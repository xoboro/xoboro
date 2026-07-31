import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Backups from '../src/admin/Backups.svelte'
import Duplicates from '../src/admin/Duplicates.svelte'
import Security from '../src/admin/Security.svelte'
import Settings from '../src/admin/Settings.svelte'
import { awaitingRestart } from '../src/lib/api/admin.js'

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

function routes(handlers) {
  return vi.fn(async (url, init = {}) => {
    for (const [match, handler] of handlers) {
      if (url.includes(match)) return typeof handler === 'function' ? handler(url, init) : handler
    }
    throw new Error(`unexpected request: ${init.method ?? 'GET'} ${url}`)
  })
}

/**
 * These are the screens where the easy implementation would say something untrue.
 *
 * Each assertion here corresponds to a server behaviour the UI must not paper over:
 * duplicate-page removal is not performed, restore is not available over HTTP, and
 * the restart-required state is two values rather than a flag.
 */

/**
 * A native page envelope, which is what every duplicate-page route actually answers.
 *
 * These tests used to mock bare arrays. That is not a shape the server produces, and
 * mocking it meant they asserted the honesty of a screen that rendered nothing at all
 * in production: an envelope is not iterable, and `undefined === 0` is false, so not
 * even the empty-state row appeared. The fixture has to be the real shape or the
 * assertions are about a different program.
 */
function page(items) {
  return {
    items,
    page: 0,
    size: 50,
    totalItems: items.length,
    totalPages: 1,
    hasPrevious: false,
    hasNext: false,
  }
}

describe('duplicate pages', () => {
  it('states that removal is not performed', async () => {
    globalThis.fetch = routes([['/duplicate-pages', reply(page([]))]])
    render(Duplicates)

    const disclosure = await screen.findByTestId('removal-disclosure')
    expect(disclosure).toBeInTheDocument()
  })

  it('renders a candidate row from the page envelope', async () => {
    // The assertion the suite was missing. Every other test here reached for a testid
    // that only exists inside a row, so all of them failed the same way for the same
    // reason and none of them said what it was.
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply(page([]))],
      ['/duplicate-pages', reply(page([{ pageHash: 'abc', sizeBytes: 1024 }]))],
    ])
    const { container } = render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('inspect-abc')).toBeInTheDocument())
    expect(container.querySelectorAll('tbody tr').length).toBeGreaterThan(0)
  })

  it('asks for a page rather than accepting the server default', async () => {
    // Without an explicit page the screen sees only the first one and nothing says so.
    const fetchImpl = routes([
      ['/duplicate-pages/decided', reply(page([]))],
      ['/duplicate-pages', reply(page([]))],
    ])
    globalThis.fetch = fetchImpl
    render(Duplicates)

    await waitFor(() => expect(fetchImpl.mock.calls.length).toBeGreaterThanOrEqual(2))
    for (const [url] of fetchImpl.mock.calls) {
      expect(url).toMatch(/[?&]page=/)
    }
  })

  it('falls back to the last page that exists after a decision empties one', async () => {
    // Recording a decision removes the hash, so deciding the last row of the last page
    // shrinks the listing by a page. Re-reading at the page the screen was on returns an
    // empty one, and the remaining candidates are then hidden behind a Previous button
    // the operator has no reason to suspect.
    let decided = false
    const fetchImpl = vi.fn(async (url, init = {}) => {
      const body = () => {
        if (init.method === 'PUT') return {}
        if (url.includes('/decided')) return page([])
        const requested = Number(new URL(url, 'http://x').searchParams.get('page'))
        // Two pages before the decision, one after.
        const totalPages = decided ? 1 : 2
        if (requested > totalPages - 1) {
          return { ...page([]), page: requested, totalItems: 1, totalPages }
        }
        return { ...page([{ pageHash: `hash-${requested}`, sizeBytes: 1 }]), page: requested, totalItems: 1, totalPages }
      }
      if (init.method === 'PUT') decided = true
      const text = JSON.stringify(body())
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        text: async () => text,
        json: async () => JSON.parse(text),
      }
    })
    globalThis.fetch = fetchImpl
    render(Duplicates)

    // Move to the second page, then decide its only row.
    await waitFor(() => expect(screen.getByTestId('inspect-hash-0')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(screen.getByTestId('inspect-hash-1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('record-IGNORE-hash-1'))

    // The surviving candidate is on screen, not stranded behind a Previous button.
    await waitFor(() => expect(screen.getByTestId('inspect-hash-0')).toBeInTheDocument())
  })

  it('does not report a page number past the end when the listing empties', async () => {
    // The paging summary is what an operator reads to work out where their rows went, so
    // it is the worst place to print something impossible. Deciding the last candidate
    // from page 2 used to leave "0 of 0 · page 2 of 1" because the clamp was skipped
    // whenever the listing came back empty — precisely this case.
    let emptied = false
    globalThis.fetch = vi.fn(async (url, init = {}) => {
      const requested = Number(new URL(url, 'http://x').searchParams.get('page') ?? 0)
      const empty = { items: [], page: requested, size: 50, totalItems: 0, totalPages: 0, hasPrevious: false, hasNext: false }
      let body
      if (init.method === 'PUT') {
        emptied = true
        body = {}
      } else if (url.includes('/decided') || emptied) {
        body = empty
      } else {
        body = {
          items: [{ pageHash: `h${requested}`, sizeBytes: 1 }],
          page: requested,
          size: 50,
          totalItems: 2,
          totalPages: 2,
          hasPrevious: requested > 0,
          hasNext: requested < 1,
        }
      }
      const text = JSON.stringify(body)
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        text: async () => text,
        json: async () => JSON.parse(text),
      }
    })
    const { container } = render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('inspect-h0')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    await waitFor(() => expect(screen.getByTestId('inspect-h1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('record-IGNORE-h1'))

    // Settle first, then assert — not both inside one `waitFor`. The paging summary is
    // already consistent *before* the re-read lands, so a `waitFor` wrapping the
    // assertion succeeded on its first attempt against the old state and reported
    // nothing about the new one. That is what made the first version of this test pass
    // with the defect still present.
    await waitFor(() => expect(screen.queryByTestId('inspect-h1')).toBeNull())

    // A number relation rather than the sentence, which is translated: whatever the
    // wording, no summary may claim a page beyond the total.
    const summaries = [...container.querySelectorAll('.total')]
    expect(summaries.length).toBeGreaterThan(0)
    for (const node of summaries) {
      const pairs = [...node.textContent.matchAll(/(\d+)\s*(?:\/|of)\s*(\d+)/g)].map((match) => [
        Number(match[1]),
        Number(match[2]),
      ])
      // The pattern has to have matched something, or this loop asserts nothing at all.
      expect(pairs.length, `no page indicator in ${JSON.stringify(node.textContent)}`).toBeGreaterThan(0)
      const [shownPage, totalPages] = pairs.at(-1)
      expect(shownPage, `claimed page ${shownPage} of ${totalPages}`).toBeLessThanOrEqual(totalPages)
    }
  })

  it('keys candidate rows by hash rather than by position', async () => {
    // An index is not an identity: on a screen where acting on a row removes it, keying by
    // position makes Svelte reuse the departed row's DOM for the survivor.
    //
    // Asserted as node identity, which is the only thing keying changes. Comparing
    // rendered content cannot see this - Svelte updates the text either way, so a test
    // that checked a row's hash matched its own buttons would pass under both keys. That
    // was this test's first version.
    let decided = false
    globalThis.fetch = vi.fn(async (url, init = {}) => {
      const body = () => {
        if (init.method === 'PUT') return {}
        if (url.includes('/decided')) return page([])
        return decided
          ? page([{ pageHash: 'second', sizeBytes: 2 }])
          : page([
              { pageHash: 'first', sizeBytes: 1 },
              { pageHash: 'second', sizeBytes: 2 },
            ])
      }
      if (init.method === 'PUT') decided = true
      const text = JSON.stringify(body())
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        text: async () => text,
        json: async () => JSON.parse(text),
      }
    })
    render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('inspect-second')).toBeInTheDocument())
    const secondRow = screen.getByTestId('inspect-second').closest('tr')

    // Decide the first row away; "second" moves from position 1 to position 0.
    await fireEvent.click(screen.getByTestId('record-IGNORE-first'))
    await waitFor(() => expect(screen.queryByTestId('inspect-first')).toBeNull())

    // Keyed by hash, "second" keeps its own row element. Keyed by index it would now be
    // rendered into the element that used to be "first".
    expect(screen.getByTestId('inspect-second').closest('tr')).toBe(secondRow)
  })

  it('never labels an action as deleting something', async () => {
    // deleteCount is always 0 and nothing executes the delete actions, so a button
    // reading "Delete pages" would be a lie in the interface.
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply(page([]))],
      ['/duplicate-pages', reply(page([{ pageHash: 'abc', sizeBytes: 1024 }]))],
    ])
    render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('record-IGNORE-abc')).toBeInTheDocument())
    // Asserted structurally rather than by matching the label's wording: this suite
    // does not test copy, and a negative copy match cannot tell "records an intent to
    // delete" from "deletes".
    for (const action of ['DELETE_MANUAL', 'DELETE_AUTO']) {
      expect(screen.getByTestId(`record-${action}-abc`)).toHaveAttribute('data-performed', 'false')
    }
    expect(screen.getByTestId('record-IGNORE-abc')).toHaveAttribute('data-performed', 'true')
  })

  it('marks a recorded delete decision as not carried out', async () => {
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply(page([{ pageHash: 'def', action: 'DELETE_AUTO' }]))],
      ['/duplicate-pages', reply(page([]))],
    ])
    render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('not-performed-def')).toBeInTheDocument())
  })

  it('does not invent a size when the pages differ', async () => {
    // A hash whose pages differ in size has no single size and the listing reports
    // null. Rendering "0" would state a fact the server did not.
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply(page([]))],
      ['/duplicate-pages', reply(page([{ pageHash: 'ghi', sizeBytes: null }]))],
    ])
    const { container } = render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('record-IGNORE-ghi')).toBeInTheDocument())
    const row = container.querySelector('tbody tr')
    expect(row.textContent).not.toMatch(/\b0\b/)
  })
})

describe('backups', () => {
  it('names the CLI restore command instead of offering a restore action', async () => {
    // Restore needs the database file offline, which conflicts with the running
    // server's lock. An operator who cannot find how to restore has a backup they
    // cannot use.
    globalThis.fetch = routes([['/backups', reply([])]])
    render(Backups)

    const command = await screen.findByTestId('restore-command')
    // The command itself is not copy - it is the literal thing an operator types.
    expect(command.textContent).toContain('xoboro restore')
    expect(command.tagName).toBe('CODE')

    // The claim is an absence, so it is asserted as one: the restore section offers no
    // action at all, only the command to run elsewhere.
    const section = screen.getByTestId('restore-section')
    expect(section.querySelectorAll('button, input, select')).toHaveLength(0)
  })
})

describe('server settings', () => {
  const SETTINGS = {
    historyRetentionDays: 0,
    authenticationActivityRetentionDays: 30,
    taskPoolSize: 4,
    rememberMeDurationDays: 14,
    serverPort: { configurationSource: null, databaseSource: 25_601, effectiveValue: 25_600 },
    serverContextPath: { configurationSource: null, databaseSource: null, effectiveValue: null },
    kepubifyPath: { configurationSource: null, databaseSource: '/bin/kepubify', effectiveValue: '/bin/kepubify' },
  }

  it('shows both values for a setting whose stored value is not running', async () => {
    // The API deliberately returns no boolean: the two values are the answer, and a
    // client-derived flag could disagree with them.
    globalThis.fetch = routes([['/server-settings', reply(SETTINGS)]])
    render(Settings)

    const pending = await screen.findByTestId('pending-serverPort')
    expect(pending.textContent).toContain('25601')
    expect(screen.getByTestId('setting-serverPort').textContent).toContain('25600')
  })

  it('does not claim a restart is needed when stored and running agree', async () => {
    globalThis.fetch = routes([['/server-settings', reply(SETTINGS)]])
    render(Settings)

    await screen.findByTestId('setting-kepubifyPath')
    expect(screen.queryByTestId('pending-kepubifyPath')).toBeNull()
    // No stored override at all is also not a pending change.
    expect(screen.queryByTestId('pending-serverContextPath')).toBeNull()
  })

  it('treats an absent stored value as no pending change', () => {
    expect(awaitingRestart({ databaseSource: null, effectiveValue: 25_600 })).toBe(false)
    expect(awaitingRestart({ databaseSource: 25_600, effectiveValue: 25_600 })).toBe(false)
    expect(awaitingRestart({ databaseSource: 25_601, effectiveValue: 25_600 })).toBe(true)
    // Compared as strings so a numeric port and its textual form do not read as a
    // pending restart.
    expect(awaitingRestart({ databaseSource: '25600', effectiveValue: 25_600 })).toBe(false)
    expect(awaitingRestart(undefined)).toBe(false)
  })
})

describe('security', () => {
  it('says why external login is read-only', async () => {
    globalThis.fetch = routes([
      ['/authentication/oauth2', reply({
        accountCreationEnabled: false,
        oidcEmailVerificationRequired: true,
        accountLinking: 'VERIFIED_EMAIL',
        providers: [],
      })],
      ['/authentication-activity', reply({ items: [], page: 0, totalItems: 0, totalPages: 0, hasPrevious: false, hasNext: false })],
    ])
    render(Security)

    // Read-only asserted as an absence of controls rather than by reading the note's
    // wording, and the note itself asserted to be present so the reason is given at all.
    expect(await screen.findByTestId('readonly-reason')).toBeInTheDocument()
    const section = screen.getByTestId('external-login-section')
    expect(section.querySelectorAll('input, select, textarea, button')).toHaveLength(0)
  })

  it('shows the account-linking policy that is in force', async () => {
    globalThis.fetch = routes([
      ['/authentication/oauth2', reply({
        accountCreationEnabled: true,
        oidcEmailVerificationRequired: false,
        accountLinking: 'NEVER',
        providers: [{ registrationId: 'synthetic', name: 'Synthetic' }],
      })],
      ['/authentication-activity', reply({ items: [], page: 0, totalItems: 0, totalPages: 0, hasPrevious: false, hasNext: false })],
    ])
    render(Security)

    const linking = await screen.findByTestId('account-linking')
    expect(linking.textContent).toBe('NEVER')
  })
})
