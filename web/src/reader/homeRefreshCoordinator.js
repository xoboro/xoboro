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
  let disposed = false

  function arm(scope) {
    if (disposed) return
    if (scope === 'full' || pending === 'none') pending = scope
    if (handle !== null) cancel(handle)
    handle = schedule(flush, delay)
  }

  function flush() {
    handle = null
    if (disposed) return
    const scope = pending
    pending = 'none'
    if (scope === 'full') {
      refreshCatalog()
      if (searchActive()) refreshSearch()
    } else if (scope === 'shelves') {
      refreshShelves()
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
