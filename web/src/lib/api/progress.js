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
 * **A conflict is a user-facing state.** The base this UI grew from wrote progress with
 * `.catch(() => {})`, which threw away the entire point: the refusal exists so a second
 * device cannot silently rewind the reader's place. So a conflict resolves in neither
 * direction on its own — it comes back as the newer position for the reader to accept
 * or decline.
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

/** What a conflict came back with, so the reader can be asked rather than overruled. */
export class ProgressConflict {
  constructor(current) {
    this.current = current
  }
}

/**
 * Writes read progress.
 *
 * @param {string} mediaItemId
 * @param {object} position
 * @param {number} [position.page] one-based page.
 * @param {object|null} [position.locator] an opaque Readium locator, stored as given.
 * @returns {Promise<ProgressConflict|null>} a conflict carrying the stored position, or
 *   `null` when the write applied.
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
    // Re-read rather than guess. The refusal says only that something newer exists, so
    // the position to offer has to come from the server.
    const current = await readMediaItem(mediaItemId)
    return new ProgressConflict(current.progress ?? null)
  }
}

/**
 * Where to resume from.
 *
 * A finished item starts again at the first page: the reader has read it, so offering
 * to resume at the last page would be offering to re-read the final page forever.
 * An unread item starts at page one rather than at zero — page numbers are one-based
 * on this surface and there is no `zero_based` parameter to be confused by.
 */
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

export function resumePage(readProgress, pageCount) {
  if (!readProgress || readProgress.completed) return 1
  const page = Number(readProgress.page)
  if (!Number.isFinite(page) || page < 1) return 1
  return pageCount > 0 ? Math.min(page, pageCount) : page
}
