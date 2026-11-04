import { session, SessionStatus } from './session.js'
import { eventHub } from './eventHub.js'
import { get } from 'svelte/store'
import { StreamStatus } from './sse.js'

/** Tab-local view snapshots. A session change also invalidates outstanding writers. */
export function createReaderNavigationState(limit = 8, canReuse = () => true) {
  const views = new Map()
  let owner = null
  let generation = 0
  let catalog = 0
  let progress = 0

  return {
    setSession(userId) {
      owner = userId || null
      generation += 1
      views.clear()
      catalog = progress = 0
    },
    invalidate(kind = 'catalog') {
      if (kind !== 'progress') catalog += 1
      if (kind !== 'catalog') progress += 1
    },
    open(userId, key) {
      // A disconnected or superseded stream cannot establish that cached data is fresh.
      if (!canReuse()) {
        catalog += 1
        progress += 1
      }
      const opened = generation
      const current = () => Boolean(userId && userId === owner && opened === generation)
      return {
        current,
        version: () => ({ catalog, progress }),
        read() {
          if (!current() || !views.has(key)) return null
          const value = views.get(key)
          views.delete(key)
          views.set(key, value)
          return structuredClone(value)
        },
        write(value) {
          if (!current()) return
          views.delete(key)
          views.set(key, structuredClone(value))
          while (views.size > limit) views.delete(views.keys().next().value)
        },
      }
    },
  }
}

export const readerNavigationState = createReaderNavigationState(8, () => get(eventHub.status) === StreamStatus.LIVE)
session.subscribe(({ status, user }) => {
  readerNavigationState.setSession(status === SessionStatus.AUTHENTICATED ? user?.id : null)
})

// These listeners stay with the application, including while a cached view is unmounted.
// They record invalidation only: hidden views never issue background requests.
eventHub.on(
  ['series.added', 'series.changed', 'series.removed', 'media-item.added', 'media-item.changed', 'media-item.removed', 'library.added', 'library.changed', 'library.removed'],
  () => readerNavigationState.invalidate('catalog'),
)
eventHub.on(['read-progress.changed', 'read-progress.removed', 'series-progress.changed', 'series-progress.removed'], () => readerNavigationState.invalidate('progress'))
eventHub.onResync((message) => {
  if (message.reason === 'revoked') readerNavigationState.setSession(null)
  else readerNavigationState.invalidate('all')
})
