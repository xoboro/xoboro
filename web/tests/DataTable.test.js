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

    // Call count is asserted alongside the argument: `toHaveBeenCalledWith(2)` is
    // satisfied by a handler that also fires a spurious extra call (e.g. `onpage(999)`
    // right after), because the matcher only checks that *some* call matched, not that
    // it was the only one.
    await fireEvent.click(screen.getByTestId('page-next-candidates'))
    expect(onpage).toHaveBeenCalledTimes(1)
    expect(onpage).toHaveBeenNthCalledWith(1, 2)

    await fireEvent.click(screen.getByTestId('page-previous-candidates'))
    expect(onpage).toHaveBeenCalledTimes(2)
    expect(onpage).toHaveBeenNthCalledWith(2, 0)
  })

  it('does not offer a page that is not there', () => {
    render(DataTableHarness, { page: envelope({ page: 0, totalPages: 1 }), name: 'only' })

    expect(screen.getByTestId('page-previous-only')).toBeDisabled()
    expect(screen.getByTestId('page-next-only')).toBeDisabled()
  })

  it('offers only next on the first page of many', () => {
    // page 0 of 3: hasPrevious is false and hasNext is true. A single-page fixture
    // (page 0 of 1) cannot distinguish `disabled={!page.hasNext}` from
    // `disabled={page.page === 0}` — both leave Next disabled there. This is the page
    // where they disagree: Next must stay enabled even though `page === 0`.
    render(DataTableHarness, { page: envelope({ page: 0, totalPages: 3 }), name: 'first' })

    expect(screen.getByTestId('page-previous-first')).toBeDisabled()
    expect(screen.getByTestId('page-next-first')).not.toBeDisabled()
  })

  it('offers both directions on a middle page', () => {
    render(DataTableHarness, { page: envelope({ page: 1, totalPages: 3 }), name: 'middle' })

    expect(screen.getByTestId('page-previous-middle')).not.toBeDisabled()
    expect(screen.getByTestId('page-next-middle')).not.toBeDisabled()
  })

  it('offers only previous on the last page of many', () => {
    // page 2 of 3: hasNext is false but page !== 0, so a guard keyed off `page === 0`
    // would wrongly leave Next enabled here while Previous must be enabled too.
    render(DataTableHarness, { page: envelope({ page: 2, totalPages: 3 }), name: 'last' })

    expect(screen.getByTestId('page-previous-last')).not.toBeDisabled()
    expect(screen.getByTestId('page-next-last')).toBeDisabled()
  })

  it('disables both paging buttons while busy, even on a middle page where both would otherwise be enabled', () => {
    // A middle page is the case where `hasPrevious` and `hasNext` are both true, so it is
    // the only fixture that proves `busy` disables independently of those flags rather
    // than coincidentally agreeing with them.
    render(DataTableHarness, { page: envelope({ page: 1, totalPages: 3 }), name: 'middle', busy: true })

    expect(screen.getByTestId('page-previous-middle')).toBeDisabled()
    expect(screen.getByTestId('page-next-middle')).toBeDisabled()
  })

  it('does not re-request the same page on a second click made while busy', async () => {
    // The defect this guards against: `onpage` reads the neighbour off the committed
    // envelope, which is last response that landed, not the one still outstanding. A
    // caller that sets `busy` for the duration of its load relies on the button being
    // truly inert while busy, not merely styled as disabled — a disabled DOM button
    // still fires a click handler wired directly to it in some setups, so this asserts
    // on the handler's call count, not just the attribute.
    const onpage = vi.fn()
    const { rerender } = render(DataTableHarness, {
      page: envelope({ page: 0, totalPages: 3 }),
      name: 'busy-case',
      onpage,
      busy: true,
    })

    await fireEvent.click(screen.getByTestId('page-next-busy-case'))
    expect(onpage).not.toHaveBeenCalled()

    // Once the load completes and busy clears, the same click must go through exactly
    // once — proving the guard blocks the outstanding load, not clicks in general.
    await rerender({ page: envelope({ page: 0, totalPages: 3 }), name: 'busy-case', onpage, busy: false })
    await fireEvent.click(screen.getByTestId('page-next-busy-case'))
    expect(onpage).toHaveBeenCalledTimes(1)
    expect(onpage).toHaveBeenNthCalledWith(1, 1)
  })
})
