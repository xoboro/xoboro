import { fireEvent, render, screen } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import ActiveFilters from '../src/reader/ActiveFilters.svelte'

/**
 * A filter that is applied but off screen is worse than no filter: the results are genuinely
 * narrower than the query the reader remembers typing, and nothing visible says why. Once the
 * facet groups could collapse and the whole filter column could close, that became the normal
 * state rather than an edge case.
 *
 * What these pin is that every applied criterion is named beside the results, that removing one
 * removes exactly one, and that `oneShot: 'any'` is read as the absence of a filter rather than
 * as a third choice worth a chip.
 */
const SERIES_CRITERIA = {
  libraryId: [],
  genre: [],
  tag: [],
  publisher: [],
  language: [],
  oneShot: 'any',
}

function mount(criteria = {}, libraries = []) {
  const onremove = vi.fn()
  render(ActiveFilters, {
    criteria: { ...SERIES_CRITERIA, ...criteria },
    libraries,
    onremove,
  })
  return onremove
}

describe('ActiveFilters', () => {
  it('names every applied value beside the results', () => {
    mount({ genre: ['drama', 'comedy'], language: ['en'] })

    expect(screen.getByTestId('active-filter-genre-drama')).toBeInTheDocument()
    expect(screen.getByTestId('active-filter-genre-comedy')).toBeInTheDocument()
    expect(screen.getByTestId('active-filter-language-en')).toBeInTheDocument()
  })

  it('removes one value and keeps the rest of its group', async () => {
    // The reason each chip exists rather than one "clear all": dropping the third of three
    // filters must not make the reader rebuild the other two.
    const onremove = mount({ genre: ['drama', 'comedy', 'action'] })

    await fireEvent.click(screen.getByTestId('active-filter-genre-comedy'))

    expect(onremove).toHaveBeenCalledWith({ genre: ['drama', 'action'] })
  })

  it('shows a library by name rather than by the id the request carries', () => {
    mount({ libraryId: ['lib-1'] }, [{ id: 'lib-1', name: 'Reference shelf' }])

    expect(screen.getByTestId('active-filter-libraryId-lib-1').textContent).toContain(
      'Reference shelf',
    )
  })

  it('falls back to the id when the library list could not be read', () => {
    // The chip still has to say what is applied; an unnamed filter is still a filter.
    mount({ libraryId: ['lib-1'] }, [])

    expect(screen.getByTestId('active-filter-libraryId-lib-1').textContent).toContain('lib-1')
  })

  it('renders nothing when nothing is applied', () => {
    mount()

    expect(screen.queryByTestId('active-filters')).toBeNull()
  })

  it('treats the default one-shot choice as no filter at all', () => {
    // `any` is the absence of this filter. A chip for it would offer to remove a filter that
    // was never applied, and clicking it would change nothing.
    mount({ oneShot: 'any' })

    expect(screen.queryByTestId('active-filters')).toBeNull()
  })

  it('offers to clear a one-shot choice that is narrowing the results', async () => {
    const onremove = mount({ oneShot: 'only' })

    await fireEvent.click(screen.getByTestId('active-filter-one-shot-only'))

    expect(onremove).toHaveBeenCalledWith({ oneShot: 'any' })
  })

  it('names a flag filter and clears it', async () => {
    const onremove = mount({ libraryId: [], onDeck: true })

    await fireEvent.click(screen.getByTestId('active-filter-onDeck'))

    expect(onremove).toHaveBeenCalledWith({ onDeck: false })
  })
})
