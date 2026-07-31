import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import Users from '../src/admin/Users.svelte'
import { session } from '../src/lib/session.js'

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

const SELF = { id: 'u1', email: 'self@example.test', roles: ['ADMIN'], sharesAllLibraries: true }
const OTHER_ADMIN = {
  id: 'u2',
  email: 'other@example.test',
  roles: ['ADMIN'],
  sharesAllLibraries: true,
}
const READER = {
  id: 'u3',
  email: 'reader@example.test',
  roles: [],
  sharedLibraryIds: ['lib-1'],
}
/** A second non-administrator, so a row can be deletable while READER is the signed-in one. */
const OTHER_READER = {
  id: 'u4',
  email: 'second@example.test',
  roles: [],
  sharedLibraryIds: [],
}

function signedInAs(user) {
  session.set({ status: 'authenticated', user })
}

/**
 * Two server protections this screen has to explain rather than merely run into.
 *
 * `cannot_delete_own_account` and `last_administrator_protected` are refusals no
 * operator can retry their way out of, so a button whose only outcome is a refusal is
 * worse than no button: it reads as a bug in the server.
 */
describe('Users', () => {
  it('explains why deleting your own account is not offered', async () => {
    signedInAs(SELF)
    globalThis.fetch = vi.fn(async () => reply([SELF, OTHER_ADMIN]))
    render(Users)

    await waitFor(() => expect(screen.getByTestId('blocked-u1')).toBeInTheDocument())
    // The reason is stated, and the control is absent rather than present-and-disabled
    // with nothing to explain it.
    expect(screen.getByTestId('blocked-u1').textContent.trim().length).toBeGreaterThan(0)
    expect(screen.queryByTestId('delete-user-u1')).toBeNull()
  })

  it('offers deletion of another administrator while one would remain', async () => {
    // The protection is about the last administrator, not about administrators. Blocking
    // every admin would leave an operator unable to remove a colleague's account at all.
    signedInAs(SELF)
    globalThis.fetch = vi.fn(async () => reply([SELF, OTHER_ADMIN]))
    render(Users)

    await waitFor(() => expect(screen.getByTestId('delete-user-u2')).toBeInTheDocument())
    expect(screen.queryByTestId('blocked-u2')).toBeNull()
  })

  it('refuses to offer deletion of the only administrator left', async () => {
    // Signed in as a non-administrator so the sole admin is somebody else — otherwise
    // the self-deletion rule would block the row for a different reason and this would
    // pass without the administrator count being consulted at all.
    signedInAs(READER)
    globalThis.fetch = vi.fn(async () => reply([OTHER_ADMIN, READER, OTHER_READER]))
    render(Users)

    await waitFor(() => expect(screen.getByTestId('blocked-u2')).toBeInTheDocument())
    expect(screen.queryByTestId('delete-user-u2')).toBeNull()
    // Another row is deletable, so the block is specific to the last administrator
    // rather than the screen refusing everything. Not READER's own row - that one is
    // blocked as self-deletion, which would have made this pass without the
    // administrator count being consulted at all.
    expect(screen.getByTestId('delete-user-u4')).toBeInTheDocument()
  })

  it('states what deleting a user destroys without inventing a count', async () => {
    // Deliberately qualitative: no endpoint counts one account's progress rows, so the
    // summary says what is destroyed instead of quoting a number the server never gave.
    signedInAs(SELF)
    globalThis.fetch = vi.fn(async () => reply([SELF, READER]))
    render(Users)

    await waitFor(() => expect(screen.getByTestId('delete-user-u3')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-user-u3'))

    const summary = await screen.findByTestId('confirm-summary')
    expect(summary.textContent).toContain(READER.email)
    // No fabricated figure. A zero here would read as "this account has no progress to
    // lose", which is not something the screen knows.
    expect(summary.textContent).not.toMatch(/\d/)
  })

  it('requires the email to be typed before deleting', async () => {
    signedInAs(SELF)
    globalThis.fetch = vi.fn(async () => reply([SELF, READER]))
    const { container } = render(Users)

    await waitFor(() => expect(screen.getByTestId('delete-user-u3')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-user-u3'))

    const confirm = await screen.findByTestId('typed-confirm')
    expect(confirm).toBeDisabled()

    const field = container.querySelector('.panel input')
    await fireEvent.input(field, { target: { value: READER.email.toUpperCase() } })
    // Compared exactly: a case-insensitive match is easier to satisfy by accident.
    expect(confirm).toBeDisabled()

    await fireEvent.input(field, { target: { value: READER.email } })
    expect(confirm).not.toBeDisabled()
  })
})
