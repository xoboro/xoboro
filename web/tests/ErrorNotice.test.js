import { fireEvent, render, screen } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import ErrorNotice from '../src/components/ErrorNotice.svelte'

/**
 * The retry button is shared by sixteen screens, so how it calls back is a contract.
 *
 * It used to be wired as `onclick={onretry}`, which handed the handler the MouseEvent as
 * its first argument. Harmless for a handler that takes none — which is fifteen of them —
 * and silently wrong for the one that takes a page number: the duplicate-pages retry
 * asked the server for `page=%5Bobject+MouseEvent%5D` and could never succeed. The bug
 * was invisible to every existing test because no test clicked retry.
 */
describe('ErrorNotice', () => {
  it('calls the retry handler with no arguments', async () => {
    // The whole defect in one assertion: an argument here is a page number, an id, or an
    // index to whatever handler a screen passes.
    const onretry = vi.fn()
    render(ErrorNotice, { error: { messageKey: 'errors.unknown' }, onretry })

    await fireEvent.click(screen.getByRole('button'))

    expect(onretry).toHaveBeenCalledTimes(1)
    expect(onretry).toHaveBeenCalledWith()
  })

  it('offers no retry when a screen passes none', async () => {
    // Absence is how a screen says "this failure cannot succeed later" — an unsupported
    // media file, say. A retry button there is one that will never work.
    render(ErrorNotice, { error: { messageKey: 'errors.unknown' } })

    expect(screen.queryByRole('button')).toBeNull()
  })

  it('renders nothing at all without an error', async () => {
    const { container } = render(ErrorNotice, { error: null, onretry: vi.fn() })

    expect(container.querySelector('.notice')).toBeNull()
  })
})
