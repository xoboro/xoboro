import { fireEvent, render, screen, waitFor } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import LibraryForm from '../src/admin/LibraryForm.svelte'

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

/** A library whose settings include far more than the form displays. */
const EXISTING = {
  id: 'lib-1',
  name: 'Synthetic Library',
  source: { provider: 'local', location: 'file:///synthetic' },
  settings: {
    scanOnStartup: true,
    scanInterval: 'DAILY',
    scanCbx: true,
    scanPdf: false,
    scanEpub: true,
    // None of these appear on the form.
    importComicInfoBook: true,
    convertToCbz: true,
    hashPages: true,
    oneshotsDirectory: 'oneshots',
    scanDirectoryExclusions: ['@eaDir'],
  },
}

describe('LibraryForm', () => {
  it('sends back settings the form does not show', async () => {
    // PUT is a full replacement, not a patch. A form that sent only its own fields
    // would reset every other setting to a default because someone corrected a typo
    // in the name — silently, and only visible weeks later when a scan behaves
    // differently.
    const fetchImpl = vi.fn().mockResolvedValue(reply({ ...EXISTING, name: 'Renamed' }))
    globalThis.fetch = fetchImpl
    render(LibraryForm, { library: EXISTING, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('library-name'), { target: { value: 'Renamed' } })
    await fireEvent.click(screen.getByTestId('library-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    const [url, init] = fetchImpl.mock.calls[0]
    expect(init.method).toBe('PUT')
    expect(url).toContain('/libraries/lib-1')

    const sent = JSON.parse(init.body)
    expect(sent.name).toBe('Renamed')
    expect(sent.settings.convertToCbz).toBe(true)
    expect(sent.settings.hashPages).toBe(true)
    expect(sent.settings.oneshotsDirectory).toBe('oneshots')
    expect(sent.settings.scanDirectoryExclusions).toEqual(['@eaDir'])
    // And the shown fields still carry what the form holds.
    expect(sent.settings.scanPdf).toBe(false)
    expect(sent.settings.scanInterval).toBe('DAILY')
  })

  it('creates with POST and states the provider explicitly', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply({ id: 'lib-9' }, 201))
    globalThis.fetch = fetchImpl
    render(LibraryForm, { library: null, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('library-name'), { target: { value: 'New' } })
    await fireEvent.input(screen.getByTestId('library-location'), {
      target: { value: 'file:///new' },
    })
    await fireEvent.click(screen.getByTestId('library-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    const [, init] = fetchImpl.mock.calls[0]
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body).source.provider).toBe('local')
  })

  it('trims the name and path rather than sending stray whitespace', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply({ id: 'lib-9' }, 201))
    globalThis.fetch = fetchImpl
    render(LibraryForm, { library: null, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('library-name'), { target: { value: '  New  ' } })
    await fireEvent.input(screen.getByTestId('library-location'), {
      target: { value: ' file:///new ' },
    })
    await fireEvent.click(screen.getByTestId('library-save'))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    const sent = JSON.parse(fetchImpl.mock.calls[0][1].body)
    expect(sent.name).toBe('New')
    expect(sent.source.location).toBe('file:///new')
  })

  it('states the location format before the operator gets it wrong', async () => {
    // The local source refuses anything without a `file:` scheme, and the server's 400
    // carries no `field`, so the field-level error cannot fire for the most likely
    // mistake — a bare path. The requirement is therefore stated up front, and in a hint
    // rather than only in the placeholder, which vanishes on the first keystroke.
    globalThis.fetch = vi.fn().mockResolvedValue(reply([]))
    render(LibraryForm, { library: null, onsaved: vi.fn(), onclose: vi.fn() })

    const hint = document.getElementById('library-location-hint')
    expect(hint).toBeTruthy()
    expect(hint.textContent).toContain('file:')
    // Associated with the input, not merely nearby, so it is announced with the field.
    expect(
      screen.getByTestId('library-location').getAttribute('aria-describedby')?.split(/\s+/),
    ).toContain('library-location-hint')
  })

  it('puts a path failure on the path field', async () => {
    // As a toast, "that path overlaps another library root" leaves the operator to
    // guess which of the two fields is wrong.
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(reply({ code: 'library_root_overlap', message: 'overlap' }, 400))
    const { container } = render(LibraryForm, {
      library: null,
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })

    await fireEvent.input(screen.getByTestId('library-name'), { target: { value: 'New' } })
    await fireEvent.input(screen.getByTestId('library-location'), {
      target: { value: 'file:///inside' },
    })
    await fireEvent.click(screen.getByTestId('library-save'))

    const location = screen.getByTestId('library-location')
    await waitFor(() => expect(location).toHaveAttribute('aria-invalid', 'true'))
    // The error id is among the descriptions rather than the whole attribute: the field
    // also points at its format hint, and an accessible name may reference several.
    expect(location.getAttribute('aria-describedby')?.split(/\s+/)).toContain(
      'library-location-error',
    )
    expect(screen.getByTestId('library-name')).not.toHaveAttribute('aria-invalid')
    expect(container.querySelector('#library-location-error')).toBeInTheDocument()
  })

  it('puts a name conflict on the name field', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(reply({ code: 'library_name_conflict', message: 'taken' }, 400))
    render(LibraryForm, { library: null, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('library-name'), { target: { value: 'Taken' } })
    await fireEvent.input(screen.getByTestId('library-location'), {
      target: { value: 'file:///new' },
    })
    await fireEvent.click(screen.getByTestId('library-save'))

    await waitFor(() =>
      expect(screen.getByTestId('library-name')).toHaveAttribute('aria-invalid', 'true'),
    )
    expect(screen.getByTestId('library-location')).not.toHaveAttribute('aria-invalid')
  })

  it('shows a non-field failure without marking any input', async () => {
    // A 403 or a network failure belongs nowhere in particular, and attaching it to an
    // arbitrary field would tell the operator to fix something that is fine.
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(reply({ code: 'library_administration_forbidden', message: 'no' }, 403))
    render(LibraryForm, { library: null, onsaved: vi.fn(), onclose: vi.fn() })

    await fireEvent.input(screen.getByTestId('library-name'), { target: { value: 'New' } })
    await fireEvent.input(screen.getByTestId('library-location'), {
      target: { value: 'file:///new' },
    })
    await fireEvent.click(screen.getByTestId('library-save'))

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByTestId('library-name')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByTestId('library-location')).not.toHaveAttribute('aria-invalid')
  })

  it('warns that saving replaces the whole configuration when editing', () => {
    // The note exists because the consequence is invisible: nothing on screen shows
    // the settings that are about to be sent along.
    const { container } = render(LibraryForm, {
      library: EXISTING,
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })
    expect(container.querySelector('.replacement-note')).toBeInTheDocument()
  })

  it('does not warn about replacement when creating', () => {
    const { container } = render(LibraryForm, {
      library: null,
      onsaved: vi.fn(),
      onclose: vi.fn(),
    })
    expect(container.querySelector('.replacement-note')).toBeNull()
  })
})
