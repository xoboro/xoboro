/**
 * In-memory reader route snapshots.
 *
 * Hash routing destroys a screen when a reader opens a series. Keeping the last rendered
 * snapshot lets the newly-created screen paint immediately on Back while it revalidates in the
 * background. Memory is deliberate: a browser reload starts clean, and one user's state must
 * never be restored into another user's session.
 */
const snapshots = new Map()

function key(route, userId) {
  return `${userId ?? 'anonymous'}:${route}`
}

export function readReaderRouteMemory(route, userId) {
  return snapshots.get(key(route, userId)) ?? null
}

export function writeReaderRouteMemory(route, userId, snapshot) {
  snapshots.set(key(route, userId), snapshot)
}

export function clearReaderRouteMemory(userId) {
  if (userId === undefined) {
    snapshots.clear()
    return
  }
  const prefix = `${userId ?? 'anonymous'}:`
  for (const storedKey of snapshots.keys()) {
    if (storedKey.startsWith(prefix)) snapshots.delete(storedKey)
  }
}
