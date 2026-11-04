import { describe, expect, it } from 'vitest'
import { createProgressWriteBarrier } from '../src/lib/progressWrites.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('resume read-after-write ordering', () => {
  it('waits for every pending write to the same item, including superseding writes', async () => {
    const barrier = createProgressWriteBarrier()
    const first = deferred(), second = deferred()
    barrier.track('item-1', first.promise)
    const wait = barrier.wait('item-1')
    let finished = false
    wait.then(() => (finished = true))
    barrier.track('item-1', second.promise)
    first.resolve()
    await Promise.resolve()
    await Promise.resolve()
    expect(finished).toBe(false)
    second.resolve()
    await wait
    expect(finished).toBe(true)
    expect(barrier.wait('item-1')).toBeNull()
  })

  it('keeps another chapter responsive and waits for whole-series mutations', async () => {
    const barrier = createProgressWriteBarrier()
    const item = deferred(), series = deferred()
    barrier.track('item-1', item.promise)
    expect(barrier.wait('item-2')).toBeNull()
    barrier.track(null, series.promise)
    const wait = barrier.wait('item-2')
    expect(wait).toBeInstanceOf(Promise)
    series.resolve()
    await wait
    item.resolve()
  })

  it('lets an aborted navigation leave without cancelling its progress write', async () => {
    const barrier = createProgressWriteBarrier()
    const write = deferred()
    barrier.track('item-1', write.promise)
    const controller = new AbortController()
    const wait = barrier.wait('item-1', { signal: controller.signal })
    controller.abort()
    await expect(wait).rejects.toHaveProperty('name', 'AbortError')
    expect(barrier.wait('item-1')).toBeInstanceOf(Promise)
    write.resolve()
  })

  it('lets failed writes settle so reads can report authoritative server progress', async () => {
    const barrier = createProgressWriteBarrier()
    const write = deferred()
    barrier.track('item-1', write.promise)
    const wait = barrier.wait('item-1')
    write.reject(new Error('Synthetic transport failure'))
    await wait
    expect(barrier.wait('item-1')).toBeNull()
  })

  it('drops the old session without letting its completion remove a new write', async () => {
    const barrier = createProgressWriteBarrier()
    const old = deferred(), fresh = deferred()
    barrier.track('item-1', old.promise)
    barrier.clear()
    expect(barrier.wait('item-1')).toBeNull()
    barrier.track('item-1', fresh.promise)
    old.resolve()
    await Promise.resolve()
    expect(barrier.wait('item-1')).toBeInstanceOf(Promise)
    fresh.resolve()
  })
})
