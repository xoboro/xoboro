import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHomeRefreshCoordinator } from '../src/reader/homeRefreshCoordinator.js'

function fixture({ libraryId = 'lib-webtoon', hasSearch = true } = {}) {
  let selected = libraryId
  let search = hasSearch
  const refreshCatalog = vi.fn()
  const refreshShelves = vi.fn()
  const refreshSearch = vi.fn()
  const coordinator = createHomeRefreshCoordinator({
    selectedLibrary: () => selected,
    searchActive: () => search,
    refreshCatalog,
    refreshShelves,
    refreshSearch,
    delay: 200,
  })
  return {
    coordinator,
    refreshCatalog,
    refreshShelves,
    refreshSearch,
    select: (libraryId) => (selected = libraryId),
    setSearch: (active) => (search = active),
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('home refresh coordinator', () => {
  it('ignores a catalog event from another selected library', () => {
    vi.useFakeTimers()
    const { coordinator, refreshCatalog, refreshSearch } = fixture()

    coordinator.onCatalog({ name: 'series.changed', libraryId: 'lib-comics' })
    vi.advanceTimersByTime(500)

    expect(refreshCatalog).not.toHaveBeenCalled()
    expect(refreshSearch).not.toHaveBeenCalled()
  })

  it('collapses a relevant catalog burst into one catalog and active-search refresh', () => {
    vi.useFakeTimers()
    const { coordinator, refreshCatalog, refreshShelves, refreshSearch } = fixture()

    coordinator.onCatalog({ name: 'series.added', libraryId: 'lib-webtoon' })
    vi.advanceTimersByTime(100)
    coordinator.onCatalog({ name: 'media-item.added', libraryId: 'lib-webtoon' })
    coordinator.onCatalog({ name: 'series.changed', libraryId: 'lib-webtoon' })
    vi.advanceTimersByTime(199)

    expect(refreshCatalog).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(refreshCatalog).toHaveBeenCalledTimes(1)
    expect(refreshSearch).toHaveBeenCalledTimes(1)
    expect(refreshShelves).not.toHaveBeenCalled()
  })

  it('lets a full invalidation dominate pending progress shelf work', () => {
    vi.useFakeTimers()
    const { coordinator, refreshCatalog, refreshShelves, refreshSearch, setSearch } = fixture()
    setSearch(false)

    coordinator.onProgress()
    coordinator.onResync()
    vi.advanceTimersByTime(200)

    expect(refreshCatalog).toHaveBeenCalledTimes(1)
    expect(refreshShelves).not.toHaveBeenCalled()
    expect(refreshSearch).not.toHaveBeenCalled()
  })

  it('refreshes shelves only for a progress burst', () => {
    vi.useFakeTimers()
    const { coordinator, refreshCatalog, refreshShelves, refreshSearch } = fixture()

    coordinator.onProgress()
    coordinator.onProgress()
    vi.advanceTimersByTime(200)

    expect(refreshShelves).toHaveBeenCalledTimes(1)
    expect(refreshCatalog).not.toHaveBeenCalled()
    expect(refreshSearch).not.toHaveBeenCalled()
  })

  it('cancels pending work when disposed', () => {
    vi.useFakeTimers()
    const { coordinator, refreshCatalog } = fixture()

    coordinator.onResync()
    coordinator.dispose()
    vi.advanceTimersByTime(500)

    expect(refreshCatalog).not.toHaveBeenCalled()
  })
})
