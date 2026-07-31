import { describe, expect, it, vi } from 'vitest'
import {
  aspectRatio,
  buildViews,
  indexOfPage,
  pageLoadPriority,
  viewKey,
} from '../src/reader/views.js'
import { createPriorityLoader } from '../src/reader/priorityLoader.js'

const PORTRAIT = { number: 1, width: 800, height: 1200 }
const LANDSCAPE = { number: 2, width: 1600, height: 1200 }
const UNKNOWN = { number: 3 }

describe('buildViews', () => {
  it('leaves every page whole when not splitting', () => {
    const views = buildViews([PORTRAIT, LANDSCAPE], false, 'ltr')
    expect(views.map((view) => view.half)).toEqual([null, null])
  })

  it('splits a landscape page and keeps a portrait one whole', () => {
    const views = buildViews([PORTRAIT, LANDSCAPE], true, 'ltr')
    expect(views).toHaveLength(3)
    expect(views[0]).toMatchObject({ page: 1, half: null })
    expect(views[1]).toMatchObject({ page: 2, half: 'L' })
    expect(views[2]).toMatchObject({ page: 2, half: 'R' })
  })

  it('orders the halves by reading direction', () => {
    // A right-to-left comic turns to the right half first. Getting this backwards shows
    // the pages in the wrong order and reads as bad source data rather than as a bug.
    const ltr = buildViews([LANDSCAPE], true, 'ltr').map((view) => view.half)
    const rtl = buildViews([LANDSCAPE], true, 'rtl').map((view) => view.half)
    expect(ltr).toEqual(['L', 'R'])
    expect(rtl).toEqual(['R', 'L'])
  })

  it('halves the width of a split view so its slot reserves the right shape', () => {
    const [left] = buildViews([LANDSCAPE], true, 'ltr')
    expect(left.width).toBe(800)
    expect(left.height).toBe(1200)
  })

  it('leaves a page whole when the manifest gave no dimensions', () => {
    // Half of an unknown page is worse than all of it, so it is not guessed at.
    expect(buildViews([UNKNOWN], true, 'ltr')).toEqual([
      { page: 3, half: null, width: null, height: null },
    ])
  })

  it('treats a square page as a single page', () => {
    const square = { number: 4, width: 1000, height: 1000 }
    expect(buildViews([square], true, 'ltr')).toHaveLength(1)
  })

  it('refuses a direction it does not know', () => {
    expect(() => buildViews([PORTRAIT], true, 'vertical')).toThrow(/unknown direction/)
  })
})

describe('viewKey', () => {
  it('distinguishes two halves of the same page', () => {
    // Both halves share a page number, so keying on the number alone would make Svelte
    // reuse one DOM node for both and the second half would never render.
    const [left, right] = buildViews([LANDSCAPE], true, 'ltr')
    expect(viewKey(left)).not.toBe(viewKey(right))
  })
})

describe('indexOfPage', () => {
  it('finds the first view showing a page', () => {
    const views = buildViews([PORTRAIT, LANDSCAPE], true, 'rtl')
    expect(indexOfPage(views, 2)).toBe(1)
  })

  it('falls back to the start for a page that is not there', () => {
    // A stored position can name a page a replaced file no longer has.
    expect(indexOfPage(buildViews([PORTRAIT], false, 'ltr'), 99)).toBe(0)
  })
})

describe('pageLoadPriority', () => {
  it('puts the current page first', () => {
    expect(pageLoadPriority(5, 5, 20)).toBe(0)
  })

  it('prefers pages ahead of the reader, in order', () => {
    expect(pageLoadPriority(6, 5, 20)).toBeLessThan(pageLoadPriority(7, 5, 20))
  })

  it('ranks every page behind the reader after every page ahead', () => {
    // The offset for a backward page starts above the furthest possible forward page,
    // so no page already read can outrank one the reader is about to reach — however
    // long the item is.
    const furthestAhead = pageLoadPriority(20, 5, 20)
    const nearestBehind = pageLoadPriority(4, 5, 20)
    expect(nearestBehind).toBeGreaterThan(furthestAhead)
  })

  it('orders backward pages nearest-first', () => {
    expect(pageLoadPriority(4, 5, 20)).toBeLessThan(pageLoadPriority(1, 5, 20))
  })
})

describe('aspectRatio', () => {
  it('reserves the shape the manifest gave', () => {
    expect(aspectRatio({ width: 800, height: 1200 })).toBe('800 / 1200')
  })

  it('reserves nothing when the manifest was silent', () => {
    // A wrong reservation shifts the page under the reader's thumb when the real image
    // lands, which is worse than a slot that grows once.
    expect(aspectRatio({ width: null, height: null })).toBeNull()
    expect(aspectRatio({})).toBeNull()
  })
})

/** A stand-in for an image element, since jsdom never actually loads one. */
function fakeImage() {
  const listeners = new Map()
  return {
    src: '',
    complete: false,
    addEventListener(name, handler) {
      if (!listeners.has(name)) listeners.set(name, new Set())
      listeners.get(name).add(handler)
    },
    removeEventListener(name, handler) {
      listeners.get(name)?.delete(handler)
    },
    removeAttribute() {
      this.src = ''
    },
    finishLoad() {
      for (const handler of listeners.get('load') ?? []) handler()
    },
  }
}

/** Runs queued microtasks by hand so the pump order can be asserted. */
function manualSchedule() {
  const queue = []
  const schedule = (fn) => queue.push(fn)
  const flush = () => {
    while (queue.length) queue.shift()()
  }
  return { schedule, flush }
}

describe('priority loader', () => {
  it('loads exactly one image at a time', () => {
    // The whole point: a browser handed fifty URLs makes the page the reader is looking
    // at compete with forty-nine prefetches, which looks like a slow server.
    const { schedule, flush } = manualSchedule()
    const loader = createPriorityLoader({ schedule })

    const first = fakeImage()
    const second = fakeImage()
    loader.load(first, { url: '/p1', priority: 0 })
    loader.load(second, { url: '/p2', priority: 1 })
    flush()

    expect(first.src).toBe('/p1')
    expect(second.src).toBe('')

    first.finishLoad()
    flush()
    expect(second.src).toBe('/p2')
  })

  it('serves the highest priority next, not the earliest queued', () => {
    // Priorities change as the reader moves, so a queue ordered on insertion would keep
    // serving pages they have already left.
    const { schedule, flush } = manualSchedule()
    const loader = createPriorityLoader({ schedule })

    const far = fakeImage()
    const near = fakeImage()
    loader.load(far, { url: '/far', priority: 9 })
    loader.load(near, { url: '/near', priority: 0 })
    flush()

    expect(near.src).toBe('/near')
    expect(far.src).toBe('')
  })

  it('reprioritises in place rather than restarting the same image', () => {
    const { schedule, flush } = manualSchedule()
    const loader = createPriorityLoader({ schedule })

    const blocker = fakeImage()
    const image = fakeImage()
    loader.load(blocker, { url: '/blocker', priority: 0 })
    const handle = loader.load(image, { url: '/page', priority: 9 })
    flush()

    handle.update({ url: '/page', priority: 1 })
    flush()
    // Still queued once, not cancelled and re-added — otherwise scrolling would cancel
    // and re-request every visible page.
    expect(loader.pending).toBe(1)
  })

  it('does not stall on an image the browser had cached', () => {
    // A cached image can be complete the moment src is set, and then no load event ever
    // fires. Without the completeness check the queue would stop on a finished task.
    const { schedule, flush } = manualSchedule()
    const loader = createPriorityLoader({ schedule })

    const cached = fakeImage()
    cached.complete = true
    const next = fakeImage()
    loader.load(cached, { url: '/cached', priority: 0 })
    loader.load(next, { url: '/next', priority: 1 })
    flush()

    expect(next.src).toBe('/next')
  })

  it('drops work queued for a previous media item', () => {
    const { schedule, flush } = manualSchedule()
    const loader = createPriorityLoader({ schedule })

    const stale = fakeImage()
    loader.load(stale, { url: '/old', priority: 0 })
    loader.reset()

    const fresh = fakeImage()
    loader.load(fresh, { url: '/new', priority: 0 })
    flush()

    expect(fresh.src).toBe('/new')
    expect(stale.src).toBe('')
  })

  it('frees the slot when a pending image is destroyed', () => {
    const { schedule, flush } = manualSchedule()
    const loader = createPriorityLoader({ schedule })

    const first = fakeImage()
    const second = fakeImage()
    const handle = loader.load(first, { url: '/p1', priority: 0 })
    loader.load(second, { url: '/p2', priority: 1 })
    flush()

    expect(first.src).toBe('/p1')
    handle.destroy()
    flush()
    // Scrolling a loading page out of view must not block every later page behind it.
    expect(second.src).toBe('/p2')
  })
})
