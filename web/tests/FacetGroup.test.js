import { fireEvent, render, screen } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import FacetGroup from '../src/reader/FacetGroup.svelte'

/**
 * A facet enumerates whatever the library happens to contain, so its size is the
 * catalog's business and not the form's.
 *
 * Rendered as a column of touch-target-height checkbox rows, one real library's genres
 * and tags came to roughly a hundred rows - about 4,400px of filter stacked above the
 * results, so the search screen showed its controls and none of its answers. What these
 * pin is that the group's height stops tracking the catalog's size, and that bounding it
 * never hides a filter the reader has actually applied.
 */
const values = (count, prefix = 'value') =>
  Array.from({ length: count }, (_, index) => `${prefix}-${index}`)

function mount(props = {}) {
  const ontoggle = vi.fn()
  render(FacetGroup, {
    parameter: 'genre',
    label: 'Genre',
    values: values(30),
    selected: [],
    ontoggle,
    ...props,
  })
  return ontoggle
}

/** Chips actually on screen, by the value each one carries. */
const shown = () =>
  screen
    .getAllByRole('checkbox')
    .map((input) => input.getAttribute('data-testid').replace('filter-genre-', ''))

describe('FacetGroup', () => {
  it('caps a long group and offers the rest', async () => {
    mount()

    // Twelve of thirty, so the group's height is decided here rather than by the library.
    expect(shown()).toHaveLength(12)
    const more = screen.getByTestId('facet-genre-more')
    expect(more.textContent).toContain('18')

    await fireEvent.click(more)

    expect(shown()).toHaveLength(30)
    // And the way back, so expanding one group does not permanently bury the next.
    expect(screen.getByTestId('facet-genre-less')).toBeInTheDocument()
  })

  it('never hides a selected value behind the cap', async () => {
    // The one that makes the cap safe rather than merely tidy. `value-29` sorts last, so
    // an unordered group would collapse it out of sight while it was still narrowing the
    // results - the filter would be applied, invisible, and the results would look wrong
    // for no reason on screen.
    mount({ selected: ['value-29'] })

    expect(shown()).toContain('value-29')
    expect(shown()[0]).toBe('value-29')
    expect(screen.getByTestId('filter-genre-value-29')).toBeChecked()
  })

  it('finds within a group large enough that reading it is the slow part', async () => {
    mount({ values: [...values(20), 'romance'] })

    await fireEvent.input(screen.getByTestId('facet-genre-find'), {
      target: { value: 'roman' },
    })

    expect(shown()).toEqual(['romance'])
    // Finding is its own way past the cap: a match beyond the twelfth chip must still
    // appear, or the box would answer "nothing matches" about a value the group holds.
    expect(screen.queryByTestId('facet-genre-more')).toBeNull()
  })

  it('offers no find box for a group small enough to read', () => {
    mount({ values: values(6) })

    expect(screen.queryByTestId('facet-genre-find')).toBeNull()
    expect(screen.queryByTestId('facet-genre-more')).toBeNull()
    expect(shown()).toHaveLength(6)
  })

  it('says when a search matches nothing rather than rendering an empty group', async () => {
    mount({ values: [...values(20)] })

    await fireEvent.input(screen.getByTestId('facet-genre-find'), {
      target: { value: 'nothing-like-this' },
    })

    // An empty chip row and a group whose values could not be read look identical.
    expect(screen.getByTestId('facet-genre-none')).toBeInTheDocument()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })

  it('reports how many of the group are selected', () => {
    mount({ selected: ['value-1', 'value-2'] })

    expect(screen.getByTestId('facet-genre-chosen').textContent).toContain('2')
  })

  it('toggles through a real checkbox, so keyboard and assistive tech come free', async () => {
    const ontoggle = mount()

    await fireEvent.click(screen.getByTestId('filter-genre-value-3'))

    expect(ontoggle).toHaveBeenCalledWith('value-3', true)
  })
})
