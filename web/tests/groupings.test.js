import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { GROUPINGS, groupingBody, moveMember } from '../src/lib/api/groupings.js'
import { SessionStatus, session } from '../src/lib/session.js'
import GroupingForm from '../src/catalog/GroupingForm.svelte'
import Groupings from '../src/catalog/Groupings.svelte'

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

beforeEach(() => {
  session.set({
    status: SessionStatus.AUTHENTICATED,
    user: { id: 'u1', email: 'a@example.invalid', roles: ['ADMIN'] },
  })
})

describe('grouping shapes', () => {
  it('uses the native member field names, not Komga’s', () => {
    // The native API says mediaItemIds and /media-items. Sending bookIds would be
    // accepted as an unknown field and the read list would come out empty.
    expect(GROUPINGS.collection.memberField).toBe('seriesIds')
    expect(GROUPINGS.readList.memberField).toBe('mediaItemIds')
    expect(GROUPINGS.readList.memberPath).toBe('media-items')
  })

  it('always sends every replaceable field', () => {
    // PUT is a full replacement of name, ordering and membership. Omitting members
    // would empty the grouping rather than leave it alone.
    const body = groupingBody('collection', { name: 'C', memberIds: ['s1'] })
    expect(body).toEqual({ name: 'C', ordered: true, seriesIds: ['s1'] })

    const readList = groupingBody('readList', { name: 'R', memberIds: [], summary: 'why' })
    expect(readList).toEqual({ name: 'R', ordered: true, mediaItemIds: [], summary: 'why' })
  })

  it('omits summary for a collection, which has none', () => {
    expect('summary' in groupingBody('collection', { name: 'C' })).toBe(false)
  })
})

describe('moveMember', () => {
  it('moves a member and keeps every other one', () => {
    expect(moveMember(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b'])
    expect(moveMember(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
  })

  it('clamps rather than dropping a member off either end', () => {
    // An off-by-one that spliced past the end would silently shorten the list, and a
    // full replacement would then delete the missing member for good.
    expect(moveMember(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c'])
    expect(moveMember(['a', 'b', 'c'], 2, 9)).toEqual(['a', 'b', 'c'])
    expect(moveMember(['a', 'b', 'c'], 5, 0)).toEqual(['a', 'b', 'c'])
  })

  it('returns the same order for a no-op move', () => {
    expect(moveMember(['a', 'b'], 1, 1)).toEqual(['a', 'b'])
  })
})

describe('grouping deletion', () => {
  it('states how many members it removes', async () => {
    // Learned the hard way one screen over: a confirmation that stops naming numbers is
    // caught by nothing unless something asserts the numbers.
    globalThis.fetch = routes([
      ['/collections', reply([{ id: 'c1', name: 'Synthetic', memberCount: 7, ordered: true }])],
    ])
    render(Groupings, { kind: 'collection' })

    await waitFor(() => expect(screen.getByTestId('delete-grouping-c1')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('delete-grouping-c1'))

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('7')
  })
})

describe('GroupingForm', () => {
  const MEMBERS = {
    items: [
      { id: 's1', title: 'First' },
      { id: 's2', title: 'Second' },
      { id: 's3', title: 'Third' },
    ],
    page: 0,
    totalItems: 3,
    totalPages: 1,
    hasPrevious: false,
    hasNext: false,
  }

  it('loads members from the member route, not from the detail response', async () => {
    // Detail and list responses deliberately do not embed member identifiers, so the
    // member route is the only place the order comes from.
    const fetchImpl = routes([['/collections/c1/series', reply(MEMBERS)]])
    globalThis.fetch = fetchImpl
    render(GroupingForm, {
      kind: 'collection',
      grouping: { id: 'c1', name: 'C', ordered: true },
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    await waitFor(() => expect(screen.getByText('First')).toBeInTheDocument())
    expect(fetchImpl.mock.calls[0][0]).toContain('/collections/c1/series')
  })

  it('sends the reordered membership on save', async () => {
    const fetchImpl = routes([
      ['/collections/c1/series', reply(MEMBERS)],
      ['/collections/c1', reply({ id: 'c1' })],
    ])
    globalThis.fetch = fetchImpl
    render(GroupingForm, {
      kind: 'collection',
      grouping: { id: 'c1', name: 'C', ordered: true },
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    await waitFor(() => expect(screen.getByTestId('move-up-s3')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('move-up-s3'))
    await fireEvent.click(screen.getByTestId('grouping-save'))

    await waitFor(() => {
      const put = fetchImpl.mock.calls.find(([, init]) => init?.method === 'PUT')
      expect(put).toBeTruthy()
      expect(JSON.parse(put[1].body).seriesIds).toEqual(['s1', 's3', 's2'])
    })
  })

  it('drops a removed member from the replacement list', async () => {
    const fetchImpl = routes([
      ['/collections/c1/series', reply(MEMBERS)],
      ['/collections/c1', reply({ id: 'c1' })],
    ])
    globalThis.fetch = fetchImpl
    render(GroupingForm, {
      kind: 'collection',
      grouping: { id: 'c1', name: 'C', ordered: true },
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    await waitFor(() => expect(screen.getByTestId('remove-s2')).toBeInTheDocument())
    await fireEvent.click(screen.getByTestId('remove-s2'))
    await fireEvent.click(screen.getByTestId('grouping-save'))

    await waitFor(() => {
      const put = fetchImpl.mock.calls.find(([, init]) => init?.method === 'PUT')
      expect(JSON.parse(put[1].body).seriesIds).toEqual(['s1', 's3'])
    })
  })

  it('offers reordering by keyboard-reachable buttons, not a drag handle alone', async () => {
    globalThis.fetch = routes([['/collections/c1/series', reply(MEMBERS)]])
    render(GroupingForm, {
      kind: 'collection',
      grouping: { id: 'c1', name: 'C', ordered: true },
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    const up = await screen.findByTestId('move-up-s2')
    expect(up.tagName).toBe('BUTTON')
    expect(up).toHaveAttribute('aria-label')
    // The first member cannot move up, and that is expressed as disabled rather than
    // as a click that silently does nothing.
    expect(screen.getByTestId('move-up-s1')).toBeDisabled()
    expect(screen.getByTestId('move-down-s3')).toBeDisabled()
  })

  it('disables reordering when the grouping is unordered', async () => {
    globalThis.fetch = routes([['/collections/c1/series', reply(MEMBERS)]])
    render(GroupingForm, {
      kind: 'collection',
      grouping: { id: 'c1', name: 'C', ordered: false },
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    await waitFor(() => expect(screen.getByTestId('move-up-s2')).toBeDisabled())
  })

  it('warns that saving drops members the editor cannot see', async () => {
    // Only visible members are listed, and a full replacement sends exactly what is
    // listed — so an administrator with a narrower grant than another could quietly
    // truncate someone else's collection.
    globalThis.fetch = routes([['/collections/c1/series', reply(MEMBERS)]])
    render(GroupingForm, {
      kind: 'collection',
      grouping: { id: 'c1', name: 'C', ordered: true },
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    await waitFor(() => expect(screen.getByText('First')).toBeInTheDocument())
    expect(screen.getByTestId('visibility-note')).toBeInTheDocument()
  })
})
