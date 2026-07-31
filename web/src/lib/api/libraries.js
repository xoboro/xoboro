import { request } from '../http.js'

/**
 * Library discovery and administration.
 *
 * Every administration route answers `403 library_administration_forbidden` for a
 * non-administrator *before* it looks up a library or reads a body, so the console
 * never needs to pre-check a role to avoid leaking whether a library exists.
 */

export function listLibraries() {
  return request('/libraries')
}

export function readLibrary(libraryId) {
  return request(`/libraries/${libraryId}`)
}

export function createLibrary(library) {
  return request('/libraries', { method: 'POST', body: library })
}

/**
 * Replaces a library completely.
 *
 * `PUT`, not `PATCH`: the native contract is a full replacement, so a caller that
 * sends only the changed fields silently resets everything it omitted.
 */
export function replaceLibrary(libraryId, library) {
  return request(`/libraries/${libraryId}`, { method: 'PUT', body: library })
}

/**
 * Deletes a library.
 *
 * Refused with `409 library_unavailable` while the storage is unreachable, so a
 * catalog is not destroyed because a mount went missing. `force` overrides that and
 * is a separate decision the UI must ask for on its own — never a pre-ticked box in
 * the first dialog.
 */
export function deleteLibrary(libraryId, { force = false } = {}) {
  // Sent only when overriding. A `force=false` on the ordinary path would be
  // harmless but it puts the word "force" in the request that is not forcing
  // anything, which is exactly the log line someone later misreads.
  return request(`/libraries/${libraryId}`, {
    method: 'DELETE',
    ...(force ? { query: { force: true } } : {}),
  })
}

/**
 * Re-checks whether the library storage is reachable.
 *
 * Synchronous and answers `200` with the updated library, unlike the task triggers
 * below. It exists because the unavailable flag is otherwise only cleared by a
 * successful scan — expensive on a large library, and its failure is what set the
 * flag in the first place. Clearing the flag is what makes a plain delete stop
 * being refused.
 */
export function recheckAvailability(libraryId) {
  return request(`/libraries/${libraryId}/availability`, { method: 'POST' })
}

/**
 * The four durable maintenance triggers.
 *
 * All answer `202 Accepted` because they enqueue background work. The UI reports
 * that the work was accepted, never that it finished — the event stream says when.
 */
export const LIBRARY_TASKS = Object.freeze(['scan', 'analyze', 'metadata-refresh', 'empty-trash'])

export function triggerLibraryTask(libraryId, task) {
  if (!LIBRARY_TASKS.includes(task)) throw new Error(`unknown library task: ${task}`)
  return request(`/libraries/${libraryId}/${task}`, { method: 'POST' })
}

/**
 * Counts what emptying the trash would destroy.
 *
 * Asked for a single row and read from `totalItems`, so the number shown in a
 * confirmation is the server's count rather than something the client estimated
 * from a page it happened to have loaded.
 */
export async function countTrashed(libraryId) {
  const [series, mediaItems] = await Promise.all([
    request('/series', { query: { libraryId, trashed: true, size: 1 } }),
    request('/media-items', { query: { libraryId, trashed: true, size: 1 } }),
  ])
  return { series: series.totalItems, mediaItems: mediaItems.totalItems }
}

/** Lists trashed entries for the trash screen. */
export function listTrashed({ libraryId = null, page = 0, size = 50 } = {}) {
  return request('/series', { query: { libraryId, trashed: true, page, size } })
}
