import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import SeriesMetadataForm from '../src/catalog/SeriesMetadataForm.svelte'

function reply(body) {
  const text = JSON.stringify(body)
  return {
    ok: true,
    status: 200,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

/**
 * What `GET /series/{id}/metadata` answers with.
 *
 * The form used to read `series.metadata` off the prop, and no route has ever sent
 * that: the series route carries these values **flat** and no locks. So every field
 * opened blank, and `stored.status` was `undefined` — which made the shipped default
 * `ONGOING` count as an edit, so opening the dialog on an ENDED series and pressing
 * save with no other change silently reset its status.
 */
const STORED = {
  title: 'Stored Title',
  titleLock: true,
  titleSort: 'Stored Title',
  titleSortLock: false,
  summary: 'Stored summary',
  summaryLock: false,
  publisher: 'Stored publisher',
  publisherLock: false,
  language: 'ko',
  languageLock: false,
  status: 'ENDED',
  statusLock: false,
  readingDirection: 'RIGHT_TO_LEFT',
  readingDirectionLock: false,
  ageRating: 15,
  ageRatingLock: false,
  genres: ['action', 'drama'],
  genresLock: false,
  tags: ['tag-a'],
  tagsLock: false,
  totalBookCount: 42,
  totalBookCountLock: false,
  sharingLabels: [],
  sharingLabelsLock: false,
  links: [],
  linksLock: false,
  alternateTitles: [],
  alternateTitlesLock: false,
}

const SERIES = { id: 's1', title: 'Stored Title' }

/** Serves the read, then whatever the save does. */
function routes(onPatch = () => reply({})) {
  return vi.fn(async (url, init = {}) => {
    if ((init.method ?? 'GET') === 'GET') return reply(STORED)
    return onPatch(url, init)
  })
}

/** Rendered and seeded. Nothing can be asserted about a patch before the read lands. */
async function open(fetchImpl) {
  globalThis.fetch = fetchImpl
  render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })
  await screen.findByTestId('md-title')
}

function patchCalls(fetchImpl) {
  return fetchImpl.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET')
}

describe('SeriesMetadataForm', () => {
  it('seeds itself from the editable surface rather than from the series prop', async () => {
    const fetchImpl = routes()
    await open(fetchImpl)

    expect(fetchImpl.mock.calls[0][0]).toContain('/series/s1/metadata')
    expect(screen.getByTestId('md-title').value).toBe('Stored Title')
    expect(screen.getByTestId('md-summary').value).toBe('Stored summary')
    expect(screen.getByTestId('md-status').value).toBe('ENDED')
  })

  /**
   * The defect this file exists to keep out.
   *
   * Every other field is protected by "unchanged is absent" happening to coincide with
   * an empty input. `status` is not: it opens on a value from a fixed list, so a blank
   * seed means the first entry, and the first entry is not what was stored.
   */
  it('does not rewrite the status when nothing was edited', async () => {
    const fetchImpl = routes()
    await open(fetchImpl)

    await fireEvent.click(screen.getByTestId('md-save'))

    expect(patchCalls(fetchImpl)).toHaveLength(0)
  })

  it('sends only the fields that changed', async () => {
    // An untouched field must be absent from the body, because absence is what
    // preserves it. Sending every field back would overwrite values another client
    // changed between load and save.
    const fetchImpl = routes(() => reply({ ...STORED, title: 'New Title' }))
    await open(fetchImpl)

    await fireEvent.input(screen.getByTestId('md-title'), { target: { value: 'New Title' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(patchCalls(fetchImpl)).toHaveLength(1))
    const [url, init] = patchCalls(fetchImpl)[0]
    expect(url).toContain('/series/s1/metadata')
    expect(init.method).toBe('PATCH')
    expect(JSON.parse(init.body)).toEqual({ title: 'New Title' })
  })

  it('empties a plain optional string with an empty string', async () => {
    // `summary` on a series preserves its value when sent as null, so blanking the
    // input has to send "" or the save would report success and change nothing.
    const fetchImpl = routes()
    await open(fetchImpl)

    await fireEvent.input(screen.getByTestId('md-summary'), { target: { value: '' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(patchCalls(fetchImpl)).toHaveLength(1))
    expect(JSON.parse(patchCalls(fetchImpl)[0][1].body)).toEqual({ summary: '' })
  })

  it('clears a patch field with an explicit null', async () => {
    const fetchImpl = routes()
    await open(fetchImpl)

    await fireEvent.change(screen.getByTestId('md-direction'), { target: { value: '' } })
    await fireEvent.input(screen.getByTestId('md-genres'), { target: { value: '' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(patchCalls(fetchImpl)).toHaveLength(1))
    expect(JSON.parse(patchCalls(fetchImpl)[0][1].body)).toEqual({
      readingDirection: null,
      genres: null,
    })
  })

  it('does not send a request when nothing was edited', async () => {
    // An empty patch answers 200 and means nothing, which reads as a save that
    // changed something.
    const fetchImpl = routes()
    globalThis.fetch = fetchImpl
    const onclose = vi.fn()
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose })
    await screen.findByTestId('md-title')

    await fireEvent.click(screen.getByTestId('md-save'))
    expect(patchCalls(fetchImpl)).toHaveLength(0)
    expect(onclose).toHaveBeenCalled()
  })

  it('leaves a locked field editable', async () => {
    // The server stores locks for the metadata-refresh pipeline; they do not stop a
    // manual patch. Disabling the input would present a guarantee the server does not
    // make, and someone would trust it to hold.
    //
    // Asserted as behaviour rather than by matching the note's wording: a negative
    // copy assertion cannot tell "does not prevent editing" from "prevents editing",
    // and this suite does not test copy.
    await open(routes())

    expect(screen.getByTestId('lock-note')).toBeInTheDocument()
    expect(screen.getByTestId('md-title-lock')).toBeChecked()

    const title = screen.getByTestId('md-title')
    expect(title).not.toBeDisabled()
    expect(title).not.toHaveAttribute('readonly')
    expect(title).not.toHaveAttribute('aria-disabled')
  })

  it('accepts an edit to a field whose lock is set', async () => {
    // The behavioural proof: a locked field can still be patched, so the UI must not
    // block what the server allows.
    const fetchImpl = routes()
    await open(fetchImpl)

    await fireEvent.input(screen.getByTestId('md-title'), { target: { value: 'Changed' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(patchCalls(fetchImpl)).toHaveLength(1))
    expect(JSON.parse(patchCalls(fetchImpl)[0][1].body)).toEqual({ title: 'Changed' })
  })

  it('sends a lock change on its own', async () => {
    const fetchImpl = routes()
    await open(fetchImpl)

    await fireEvent.click(screen.getByTestId('md-title-lock'))
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(patchCalls(fetchImpl)).toHaveLength(1))
    expect(JSON.parse(patchCalls(fetchImpl)[0][1].body)).toEqual({ titleLock: false })
  })

  it('treats an unchanged age rating as unchanged despite the string input', async () => {
    // A number input hands back a string, so a naive comparison would send 15 every
    // time and count as an edit on every save.
    const fetchImpl = routes()
    await open(fetchImpl)

    await fireEvent.input(screen.getByTestId('md-age'), { target: { value: '15' } })
    await fireEvent.click(screen.getByTestId('md-save'))
    expect(patchCalls(fetchImpl)).toHaveLength(0)
  })
})
