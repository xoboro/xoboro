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

const SERIES = {
  id: 's1',
  metadata: {
    title: 'Stored Title',
    summary: 'Stored summary',
    publisher: 'Stored publisher',
    language: 'ko',
    status: 'ONGOING',
    readingDirection: 'RIGHT_TO_LEFT',
    ageRating: 15,
    genres: ['action', 'drama'],
    tags: ['tag-a'],
    titleLock: true,
  },
}

describe('SeriesMetadataForm', () => {
  it('sends only the fields that changed', async () => {
    // An untouched field must be absent from the body, because absence is what
    // preserves it. Sending every field back would overwrite values another client
    // changed between load and save.
    const fetchImpl = vi.fn().mockResolvedValue(reply({ title: 'New Title' }))
    globalThis.fetch = fetchImpl
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('md-title'), { target: { value: 'New Title' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toContain('/series/s1/metadata')
    expect(init.method).toBe('PATCH')
    expect(JSON.parse(init.body)).toEqual({ title: 'New Title' })
  })

  it('empties a plain optional string with an empty string', async () => {
    // `summary` on a series preserves its value when sent as null, so blanking the
    // input has to send "" or the save would report success and change nothing.
    const fetchImpl = vi.fn().mockResolvedValue(reply({}))
    globalThis.fetch = fetchImpl
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('md-summary'), { target: { value: '' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ summary: '' })
  })

  it('clears a patch field with an explicit null', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply({}))
    globalThis.fetch = fetchImpl
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.change(screen.getByTestId('md-direction'), { target: { value: '' } })
    await fireEvent.input(screen.getByTestId('md-genres'), { target: { value: '' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({
      readingDirection: null,
      genres: null,
    })
  })

  it('does not send a request when nothing was edited', async () => {
    // An empty patch answers 200 and means nothing, which reads as a save that
    // changed something.
    const fetchImpl = vi.fn()
    globalThis.fetch = fetchImpl
    const onclose = vi.fn()
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose })

    await fireEvent.click(screen.getByTestId('md-save'))
    expect(fetchImpl).not.toHaveBeenCalled()
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
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

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
    const fetchImpl = vi.fn().mockResolvedValue(reply({}))
    globalThis.fetch = fetchImpl
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('md-title'), { target: { value: 'Changed' } })
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ title: 'Changed' })
  })

  it('sends a lock change on its own', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply({}))
    globalThis.fetch = fetchImpl
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.click(screen.getByTestId('md-title-lock'))
    await fireEvent.click(screen.getByTestId('md-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ titleLock: false })
  })

  it('treats an unchanged age rating as unchanged despite the string input', async () => {
    // A number input hands back a string, so a naive comparison would send 15 every
    // time and count as an edit on every save.
    const fetchImpl = vi.fn()
    globalThis.fetch = fetchImpl
    render(SeriesMetadataForm, { series: SERIES, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('md-age'), { target: { value: '15' } })
    await fireEvent.click(screen.getByTestId('md-save'))
    expect(fetchImpl).not.toHaveBeenCalled()
  })
})
