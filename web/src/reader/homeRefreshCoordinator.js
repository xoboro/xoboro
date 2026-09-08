/**
 * Coalesces the reader home's event-driven invalidations.
 *
 * Catalog scans can publish thousands of item and series events. The home screen
 * needs their final state, not one five-request refresh per row, so relevant events
 * share one trailing timer. A full catalog invalidation contains the shelf-only work
 * requested by progress and therefore dominates it.
 */
export function createHomeRefreshCoordinator({
  selectedLibrary,
  searchActive,
  refreshCatalog,
  refreshShelves,
  refreshSearch,
  schedule = (callback, delay) => setTimeout(callback, delay),
  cancel = (handle) => clearTimeout(handle),
  delay = 200,
}) {
  let pending = 'none'
  let handle = null
  let running = false
  let disposed = false

  function start(callback) {
    try {
      return Promise.resolve(callback())
    } catch (error) {
      return Promise.reject(error)
    }
  }

  function arm(scope) {
    if (disposed) return
    if (scope === 'full' || pending === 'none') pending = scope
    if (running) return
    if (handle !== null) cancel(handle)
    handle = schedule(flush, delay)
  }

  async function flush() {
    handle = null
    if (disposed || running) return
    const scope = pending
    pending = 'none'
    if (scope === 'none') return
    running = true
    try {
      if (scope === 'full') {
        const work = [start(refreshCatalog)]
        if (searchActive()) work.push(start(refreshSearch))
        await Promise.allSettled(work)
      } else {
        await start(refreshShelves).catch(() => {})
      }
    } finally {
      running = false
      if (!disposed && pending !== 'none') {
        handle = schedule(flush, delay)
      }
    }
  }

  return {
    onCatalog(message) {
      const selected = selectedLibrary()
      if (selected !== null && message.libraryId !== selected) return
      arm('full')
    },

    onProgress() {
      arm('shelves')
    },

    onResync() {
      arm('full')
    },

    dispose() {
      disposed = true
      pending = 'none'
      if (handle !== null) cancel(handle)
      handle = null
    },
  }
}
