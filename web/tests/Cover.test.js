import { render, waitFor } from '@testing-library/svelte'
import { fireEvent } from '@testing-library/dom'
import { describe, expect, it, vi } from 'vitest'
import Cover from '../src/components/Cover.svelte'

/**
 * A cover while it is still arriving.
 *
 * The component drew nothing until the image decoded and only showed a placeholder once
 * it had failed, so a grid of covers on a first visit was a page of empty boxes — which
 * reads as a library with no artwork rather than as artwork on its way. The reference
 * reader shows a placeholder immediately and fades the image in over it.
 */
describe('Cover', () => {
  it('shows a placeholder before the image has loaded', () => {
    const { container } = render(Cover, { src: '/artwork/s1' })

    expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeTruthy()
    // The image is in the tree from the start - it has to be, or it would never load -
    // but it is not yet the thing being shown.
    const image = container.querySelector('img')
    expect(image).toBeTruthy()
    expect(image.classList.contains('ready')).toBe(false)
  })

  it('shows the image once it has loaded, and stops showing the placeholder', async () => {
    const { container } = render(Cover, { src: '/artwork/s1' })

    await fireEvent.load(container.querySelector('img'))

    expect(container.querySelector('img').classList.contains('ready')).toBe(true)
    expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeNull()
  })

  /**
   * Returning to a catalogue often creates a new component for an image the browser already
   * has. The cached image can be complete before the new load listener observes an event, so
   * waiting for that event alone leaves a valid cover behind the placeholder forever.
   */
  it('shows a successfully cached image even when no load event is observed', async () => {
    const complete = vi.spyOn(HTMLImageElement.prototype, 'complete', 'get').mockReturnValue(true)
    const width = vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(223)

    const { container } = render(Cover, { src: '/artwork/already-cached' })

    await waitFor(() =>
      expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeNull(),
    )
    expect(container.querySelector('img')).toHaveClass('ready')

    complete.mockRestore()
    width.mockRestore()
  })

  it('keeps the placeholder once the retries are exhausted', async () => {
    // Artwork is produced during analysis, and an EPUB with no declared cover never gets
    // any. A permanent placeholder is the honest rendering of that — but only after the
    // transient case has been given its chances.
    const { container } = render(Cover, { src: '/artwork/s1', retryDelays: [] })

    await fireEvent.error(container.querySelector('img'))

    expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
  })

  /**
   * A cover missing because analysis has not reached it yet is not a cover that does not
   * exist. A reader watching a scan fill a library should not have to reload to see it.
   */
  it('asks again for artwork that was not there yet', async () => {
    const { container } = render(Cover, { src: '/artwork/s1', retryDelays: [5] })

    await fireEvent.error(container.querySelector('img'))
    // Still trying, so nothing is declared missing.
    expect(container.querySelector('img')).toBeTruthy()

    await waitFor(() =>
      expect(container.querySelector('img').getAttribute('src')).toContain('retry=1'),
    )
  })

  it('gives up rather than asking forever', async () => {
    const { container } = render(Cover, { src: '/artwork/s1', retryDelays: [1] })

    await fireEvent.error(container.querySelector('img'))
    await waitFor(() =>
      expect(container.querySelector('img').getAttribute('src')).toContain('retry=1'),
    )
    await fireEvent.error(container.querySelector('img'))

    await waitFor(() => expect(container.querySelector('img')).toBeNull())
    expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeTruthy()
  })

  it('starts again from the placeholder when the source changes', async () => {
    // The same component instance is reused as a grid pages: leaving `ready` set would
    // show the previous series' cover until the new one decoded.
    const { container, rerender } = render(Cover, { src: '/artwork/s1' })
    await fireEvent.load(container.querySelector('img'))
    expect(container.querySelector('img').classList.contains('ready')).toBe(true)

    await rerender({ src: '/artwork/s2' })

    expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeTruthy()
    expect(container.querySelector('img').classList.contains('ready')).toBe(false)
  })
})
