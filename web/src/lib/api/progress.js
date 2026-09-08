import { Treatment } from '../errors.js'
import { deviceIdentity } from '../device.js'
import { request } from '../http.js'
import { readMediaItem } from './catalog.js'

/**
 * Read progress.
 *
 * Two properties of the contract shape everything here.
 *
 * **`modifiedAtMillis` is the client's own clock, and it is the sole conflict-ordering
 * key.** The server does not substitute its own. A value older than *or equal to* the
 * stored one is refused with `409 stale_progress` — equal counts as stale, so two
 * writes within the same millisecond would see the second rejected. The clock below is
 * therefore strictly increasing rather than a bare `Date.now()`.
 *
 * **A stale write is already resolved.** The server kept the newer row, so a delayed
 * request must not interrupt reading with a modal or retry and overwrite that row. The
 * client only learns the stored clock, allowing the next real movement to save normally.
 */

let lastStamp = 0

/**
 * A strictly increasing timestamp.
 *
 * Equal is stale, so a monotonic counter is required rather than optional. When the
 * wall clock has not advanced — or has gone backwards, which it does across a
 * daylight-saving or NTP adjustment — this returns the previous value plus one, which
 * still orders correctly against what the server stored.
 */
export function progressStamp(now = () => Date.now()) {
  const wall = now()
  lastStamp = wall > lastStamp ? wall : lastStamp + 1
  return lastStamp
}

/** Reset for tests; the counter is module state precisely so it survives navigations. */
export function resetProgressClock() {
  lastStamp = 0
}

/**
 * Writes read progress.
 *
 * @param {string} mediaItemId
 * @param {object} position
 * @param {number} [position.page] one-based page.
 * @param {object|null} [position.locator] an opaque Readium locator, stored as given.
 * @returns {Promise<null>} once the write was applied or a newer stored write won.
 */
export async function writeProgress(mediaItemId, { page = undefined, locator = null } = {}) {
  const { deviceId, deviceName } = deviceIdentity()
  try {
    await request(`/media-items/${mediaItemId}/progress`, {
      method: 'PUT',
      body: {
        ...(page === undefined ? {} : { page }),
        ...(locator ? { locator } : {}),
        deviceId,
        deviceName,
        modifiedAtMillis: progressStamp(),
      },
    })
    return null
  } catch (error) {
    if (error.treatment !== Treatment.PROGRESS_CONFLICT) throw error
    // A stale request has already lost safely: the server kept the newer row. Learn its
    // clock so later movement can be saved, but do not interrupt reading or retry the
    // stale page and accidentally rewind an out-of-order write from this same reader.
    const current = await readMediaItem(mediaItemId)
    const storedStamp = Number(current.progress?.readAtMillis)
    if (Number.isFinite(storedStamp)) lastStamp = Math.max(lastStamp, storedStamp)
    return null
  }
}

/**
 * Clears this reader's progress for one item.
 *
 * The surface offered only `PUT`, so "unread" was a state the server could hold and no
 * client could ask for: a chapter opened by accident could only be undone by reading it
 * to the end. Idempotent on the server, so this needs no "was it started" check first.
 */
export async function clearProgress(mediaItemId) {
  await request(`/media-items/${mediaItemId}/progress`, { method: 'DELETE' })
}

/** Marks every item in a series read, or clears every one of them. */
export async function writeSeriesProgress(seriesId, { read }) {
  await request(`/series/${seriesId}/progress`, { method: read ? 'PUT' : 'DELETE' })
}

/**
 * How far into an item a reader has got, as a whole percentage.
 *
 * Drawn as a bar beside the words. "Page 5" does not say whether that is the start or
 * nearly the end without knowing how long the item is, and the reader should not have to
 * do that arithmetic.
 *
 * Two cases decide the shape of this. A **finished** item is a full bar whatever page it
 * stopped on, because a chapter can be marked read from the list without being opened and
 * its stored page is then wherever the reader last was. An item with **no page count** —
 * indexed but not yet analyzed — is zero rather than a division by zero: `Infinity` in a
 * width renders as a full bar, which is the opposite of what is true.
 */
export function percentRead(progress, pageCount) {
  if (!progress) return 0
  if (progress.completed) return 100
  const total = Number(pageCount)
  const page = Number(progress.page)
  if (!Number.isFinite(total) || total <= 0) return 0
  if (!Number.isFinite(page) || page <= 0) return 0
  return Math.min(100, Math.round((page / total) * 100))
}

/**
 * Where to resume from.
 *
 * A finished item starts again at the first page: the reader has read it, so offering
 * to resume at the last page would be offering to re-read the final page forever.
 * An unread item starts at page one rather than at zero — page numbers are one-based
 * on this surface and there is no `zero_based` parameter to be confused by.
 */
export function resumePage(readProgress, pageCount) {
  if (!readProgress || readProgress.completed) return 1
  const page = Number(readProgress.page)
  if (!Number.isFinite(page) || page < 1) return 1
  return pageCount > 0 ? Math.min(page, pageCount) : page
}
