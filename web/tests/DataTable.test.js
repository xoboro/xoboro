import { fireEvent, render, screen } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import DataTableHarness from './helpers/DataTableHarness.svelte'

/**
 * The shared listing every admin screen pages through.
 *
 * Its paging hooks are namespaced by `name` because two listings on one screen otherwise
 * emit the same `data-testid`, and the ambiguity is silent: a test asking for
 * `page-next` gets whichever table rendered first.
 */
function envelope({ page = 0, totalPages = 3, items = [{ id: 'row-1' }] } = {}) {
  return {
    items,
    page,
    size: 20,
    totalItems: totalPages * 20,
    totalPages,
    hasPrevious: page > 0,
    hasNext: page < totalPages - 1,
  }
}

describe('DataTable', () => {
  it('refuses to render without a name', () => {
    // Documented as "required in practice" and enforced by nothing, it emitted
    // `page-next-undefined`: a hook that exists, matches nothing, and collides with the
    // next caller that also forgets.
    expect(() => render(DataTableHarness, { page: envelope() })).toThrow(/requires a name/)
  })

  it('namespaces its paging hooks by name', () => {
    render(DataTableHarness, { page: envelope({ page: 1 }), name: 'candidates' })

    expect(screen.getByTestId('page-previous-candidates')).toBeInTheDocument()
    expect(screen.getByTestId('page-next-candidates')).toBeInTheDocument()
    // The un-namespaced hook must not exist at all, or a test written against it passes
    // by picking a table at random.
    expect(screen.queryByTestId('page-next')).toBeNull()
  })

  it('asks for the next page by number rather than by direction', async () => {
    const onpage = vi.fn()
    render(DataTableHarness, { page: envelope({ page: 1 }), name: 'candidates', onpage })

    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    expect(onpage).toHaveBeenCalledWith(2)

    await fireEvent.click(screen.getByTestId('page-previous-candidates'))
    expect(onpage).toHaveBeenCalledWith(0)
  })

  it('does not offer a page that is not there', () => {
    render(DataTableHarness, { page: envelope({ page: 0, totalPages: 1 }), name: 'only' })

    expect(screen.getByTestId('page-previous-only')).toBeDisabled()
    expect(screen.getByTestId('page-next-only')).toBeDisabled()
  })
})
