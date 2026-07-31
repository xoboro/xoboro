import { render, screen, waitFor } from '@testing-library/svelte'
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
describe('duplicate pages', () => {
  it('states that removal is not performed', async () => {
    globalThis.fetch = routes([['/duplicate-pages', reply([])]])
    render(Duplicates)

    const disclosure = await screen.findByTestId('removal-disclosure')
    expect(disclosure).toBeInTheDocument()
  })

  it('never labels an action as deleting something', async () => {
    // deleteCount is always 0 and nothing executes the delete actions, so a button
    // reading "Delete pages" would be a lie in the interface.
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply([])],
      ['/duplicate-pages', reply([{ pageHash: 'abc', sizeBytes: 1024 }])],
    ])
    render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('record-IGNORE-abc')).toBeInTheDocument())
    for (const action of ['DELETE_MANUAL', 'DELETE_AUTO']) {
      const label = screen.getByTestId(`record-${action}-abc`).textContent
      // The label must say it records an intention, not that it deletes.
      expect(label).toMatch(/intent|기록/i)
    }
  })

  it('marks a recorded delete decision as not carried out', async () => {
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply([{ pageHash: 'def', action: 'DELETE_AUTO' }])],
      ['/duplicate-pages', reply([])],
    ])
    render(Duplicates)

    await waitFor(() => expect(screen.getByTestId('not-performed-def')).toBeInTheDocument())
  })

  it('does not invent a size when the pages differ', async () => {
    // A hash whose pages differ in size has no single size and the listing reports
    // null. Rendering "0" would state a fact the server did not.
    globalThis.fetch = routes([
      ['/duplicate-pages/decided', reply([])],
      ['/duplicate-pages', reply([{ pageHash: 'ghi', sizeBytes: null }])],
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
    expect(command.textContent).toContain('xoboro restore')

    const labels = screen.getAllByRole('button').map((node) => node.textContent).join(' ')
    expect(labels).not.toMatch(/restore|복원/i)
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

    const reason = await screen.findByTestId('readonly-reason')
    // Names the actual reason - secrets staying out of the database - rather than
    // "not supported".
    expect(reason.textContent).toMatch(/database|데이터베이스/i)
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
