import { render, waitFor } from '@testing-library/svelte'
import { fireEvent } from '@testing-library/dom'
import { tick } from 'svelte'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Cover from '../src/components/Cover.svelte'

function coverVisibility() {
  let notify
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(callback) {
        notify = callback
      }

      observe() {}
      disconnect() {}
    },
  )

  return {
    async set(isIntersecting) {
      notify?.([{ isIntersecting }])
      await tick()
    },
  }
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/**
 * A cover while it is still arriving.
 *
 * The component drew nothing until the image decoded and only showed a placeholder once
 * it had failed, so a grid of covers on a first visit was a page of empty boxes — which
 * reads as a library with no artwork rather than as artwork on its way. The reference
 * reader shows a placeholder immediately and fades the image in over it.
 */
describe('Cover', () => {
  it('starts shimmer only after a visible cover has waited 250ms', async () => {
    vi.useFakeTimers()
    const visibility = coverVisibility()
    const { container } = render(Cover, { src: '/artwork/s1' })
    const placeholder = container.querySelector('[data-testid="cover-placeholder"]')

    await vi.advanceTimersByTimeAsync(1000)
    expect(placeholder.classList.contains('shimmering')).toBe(false)

    await visibility.set(true)
    await vi.advanceTimersByTimeAsync(249)
    expect(placeholder.classList.contains('shimmering')).toBe(false)

    await vi.advanceTimersByTimeAsync(1)
    expect(placeholder.classList.contains('shimmering')).toBe(true)
  })

  it('stops shimmer while missing artwork waits to retry', async () => {
    vi.useFakeTimers()
    const visibility = coverVisibility()
    const { container } = render(Cover, { src: '/artwork/s1', retryDelays: [1000] })
    const placeholder = container.querySelector('[data-testid="cover-placeholder"]')

    await visibility.set(true)
    await vi.advanceTimersByTimeAsync(250)
    expect(placeholder.classList.contains('shimmering')).toBe(true)

    await fireEvent.error(container.querySelector('img'))

    expect(placeholder.classList.contains('shimmering')).toBe(false)

    await visibility.set(false)
    await visibility.set(true)
    await vi.advanceTimersByTimeAsync(250)

    expect(placeholder.classList.contains('shimmering')).toBe(false)
  })

  it('stops shimmer when the cover leaves the viewport', async () => {
    vi.useFakeTimers()
    const visibility = coverVisibility()
    const { container } = render(Cover, { src: '/artwork/s1' })
    const placeholder = container.querySelector('[data-testid="cover-placeholder"]')

    await visibility.set(true)
    await vi.advanceTimersByTimeAsync(250)
    expect(placeholder.classList.contains('shimmering')).toBe(true)

    await visibility.set(false)

    expect(placeholder.classList.contains('shimmering')).toBe(false)
  })

  it('ignores a queued visibility callback after the cover is destroyed', async () => {
    vi.useFakeTimers()
    const visibility = coverVisibility()
    const { unmount } = render(Cover, { src: '/artwork/s1' })

    unmount()
    expect(vi.getTimerCount()).toBe(0)

    await visibility.set(true)

    expect(vi.getTimerCount()).toBe(0)
  })

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
