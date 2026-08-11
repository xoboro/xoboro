import { render, waitFor } from '@testing-library/svelte'
import { fireEvent } from '@testing-library/dom'
import { describe, expect, it, vi } from 'vitest'
import Cover from '../src/components/Cover.svelte'

/**
 * The retry must be bounded and must not outlive the component.
 *
 * A cover that keeps asking is a cover that keeps asking the server, once per card, on a
 * grid of a hundred. And a timer that survives an unmount fires against a component that
 * is gone, which in a paging grid happens on every page turn.
 */
describe('Cover retry bounds', () => {
  it('stops after the configured number of attempts', async () => {
    const { container } = render(Cover, { src: '/artwork/s1', retryDelays: [1, 1, 1] })

    // The src is captured as a string, not as the node. Svelte updates the attribute on
    // the same element rather than replacing it, so holding the node and comparing it to
    // itself compares a value with itself and can never differ - the check would hang
    // until it timed out and blame the component for it.
    const seen = []
    for (let round = 0; round < 5; round += 1) {
      const image = container.querySelector('img')
      if (!image) break
      const before = image.getAttribute('src')
      seen.push(before)
      await fireEvent.error(image)
      await waitFor(() => {
        const next = container.querySelector('img')
        expect(next === null || next.getAttribute('src') !== before).toBe(true)
      })
    }

    // The original plus one request per delay, and then it stops asking.
    expect(seen).toHaveLength(4)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('[data-testid="cover-placeholder"]')).toBeTruthy()
  })

  it('does not fire a scheduled retry after the component is gone', async () => {
    /*
     * Counted across the unmount boundary, and against the id that is actually pending.
     *
     * `toHaveBeenCalled()` here could not fail: `clearTimeout` has already run twice by
     * this point - once from the effect that resets on mount, once from `missing()` before
     * it schedules - so deleting the `onDestroy` entirely left the assertion green. It was
     * a test of the setup, not of the teardown.
     */
    const scheduled = []
    const setSpy = vi.spyOn(globalThis, 'setTimeout')
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    const { container, unmount } = render(Cover, { src: '/artwork/s1', retryDelays: [10_000] })

    await fireEvent.error(container.querySelector('img'))
    scheduled.push(...setSpy.mock.results.map((result) => result.value))
    const pending = scheduled.at(-1)
    const before = clearSpy.mock.calls.length

    unmount()

    expect(clearSpy.mock.calls.length).toBeGreaterThan(before)
    // The one that was actually waiting, not merely some timer.
    expect(clearSpy.mock.calls.slice(before).flat()).toContain(pending)

    setSpy.mockRestore()
    clearSpy.mockRestore()
  })
})
