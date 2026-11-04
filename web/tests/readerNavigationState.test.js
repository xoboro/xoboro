import { describe, expect, it } from 'vitest'
import { createReaderNavigationState } from '../src/lib/readerNavigationState.js'

describe('reader navigation snapshots', () => {
  it('copies snapshots and evicts the least recently visited view', () => {
    const state = createReaderNavigationState(2)
    state.setSession('reader-a')
    const first = state.open('reader-a', 'first')
    const source = { items: [{ title: 'Synthetic title' }] }
    first.write(source)
    source.items[0].title = 'Changed source'
    const copy = first.read()
    copy.items[0].title = 'Changed copy'
    expect(first.read().items[0].title).toBe('Synthetic title')
    state.open('reader-a', 'second').write({ page: 1 })
    first.read()
    state.open('reader-a', 'third').write({ page: 2 })
    expect(first.read()).not.toBeNull()
    expect(state.open('reader-a', 'second').read()).toBeNull()
  })

  it('isolates readers, tabs without a session, and outstanding writers across sign-out', () => {
    const state = createReaderNavigationState()
    state.setSession('reader-a')
    const old = state.open('reader-a', 'series:s1')
    old.write({ page: 1 })
    expect(state.open('reader-b', 'series:s1').read()).toBeNull()
    state.setSession(null)
    old.write({ page: 2 })
    state.open(null, 'series:s1').write({ page: 3 })
    state.setSession('reader-a')
    expect(old.current()).toBe(false)
    old.write({ page: 4 })
    expect(state.open('reader-a', 'series:s1').read()).toBeNull()
  })

  it('records catalog and progress invalidations without discarding visible snapshots', () => {
    const state = createReaderNavigationState()
    state.setSession('reader-a')
    const view = state.open('reader-a', 'home')
    const version = view.version()
    view.write({ page: 1, version })
    state.invalidate('progress')
    expect(view.version()).toEqual({ catalog: version.catalog, progress: version.progress + 1 })
    state.invalidate('catalog')
    state.invalidate('all')
    expect(view.version()).toEqual({ catalog: version.catalog + 2, progress: version.progress + 2 })
    expect(view.read()).toEqual({ page: 1, version })
  })

  it('revalidates on return when the event stream cannot vouch for freshness', () => {
    let live = true
    const state = createReaderNavigationState(8, () => live)
    state.setSession('reader-a')
    const view = state.open('reader-a', 'series:s1')
    view.write({ page: 1, version: view.version() })
    live = false
    const returned = state.open('reader-a', 'series:s1')
    expect(returned.read().page).toBe(1)
    expect(returned.read().version).not.toEqual(returned.version())
  })
})
