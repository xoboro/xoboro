/**
 * Reading preferences, per user and per series, in this browser.
 *
 * Deliberately local rather than server-side. The mobile clients are planned
 * local-first — a remote Xoboro is optional there — so a preference that only
 * exists on the server would simply be missing in the case those clients treat as
 * normal. Read progress is the opposite kind of value: losing it costs a reader
 * their place, so it stays on the server where every client can reach it. Losing a
 * sort order costs one tap.
 *
 * Keyed by user as well as series because one browser can be shared. Without the
 * user in the key, a household would silently inherit each other's choices, which
 * reads as the app changing settings on its own.
 *
 * Every accessor tolerates storage being unavailable. Safari in private mode throws
 * on `localStorage` access rather than returning null, and a refused read must cost
 * the preference, not the screen.
 */

const NAMESPACE = 'xoboro.pref'

/**
 * Where Reader.svelte kept its view settings before they became per-series.
 *
 * Still read, as the fallback below a per-series value: a reader who had already
 * chosen scroll-and-LTR keeps it for every series they have not overridden, rather
 * than being reset by the upgrade.
 */
const LEGACY_READER_NAMESPACE = 'xoboro.reader'

/** Preference names this module knows. Anything else is a caller's typo. */
export const Preference = Object.freeze({
  /** Series listing order: 'newest' or 'oldest'. */
  SORT: 'sort',
  /** Reader layout: one of Reader.svelte's MODES. */
  MODE: 'mode',
  /** Reading direction: 'ltr' or 'rtl'. */
  DIRECTION: 'direction',
})

/** Names that fall back to a legacy global value when no per-series one is set. */
const LEGACY_BACKED = new Set([Preference.MODE, Preference.DIRECTION])

function keyFor(userId, seriesId, name) {
  // A missing user or series becomes an explicit scope rather than "undefined" in
  // the key, so an anonymous or series-less caller cannot collide with a real one.
  return `${NAMESPACE}.${userId || 'anonymous'}.${seriesId || 'global'}.${name}`
}

function readRaw(key) {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

/**
 * The stored preference, or `fallback` when nothing is stored.
 *
 * @param {string|null|undefined} userId
 * @param {string|null|undefined} seriesId
 * @param {string} name one of {@link Preference}
 * @param {string} fallback
 * @returns {string}
 */
export function readPreference(userId, seriesId, name, fallback) {
  const own = readRaw(keyFor(userId, seriesId, name))
  if (own !== null) return own
  if (LEGACY_BACKED.has(name)) {
    const legacy = readRaw(`${LEGACY_READER_NAMESPACE}.${name}`)
    if (legacy !== null) return legacy
  }
  return fallback
}

/**
 * Stores a preference. A refused write is dropped rather than raised.
 *
 * @param {string|null|undefined} userId
 * @param {string|null|undefined} seriesId
 * @param {string} name one of {@link Preference}
 * @param {string} value
 */
export function writePreference(userId, seriesId, name, value) {
  try {
    localStorage.setItem(keyFor(userId, seriesId, name), value)
  } catch {
    // A refused write costs the preference, not the reading session.
  }
}

/**
 * Constrains a stored value to a known set.
 *
 * Storage is editable by hand and survives a deploy that removes an option, so a
 * value that is no longer valid must not reach the component that switches on it.
 *
 * @param {string} value
 * @param {readonly string[]} allowed
 * @param {string} fallback
 */
export function oneOf(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback
}
