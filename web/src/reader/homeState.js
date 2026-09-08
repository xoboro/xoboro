const NAMESPACE = 'xoboro.home'

function scope(value, fallback) {
  return encodeURIComponent(value || fallback)
}

function keyFor(userId, libraryId) {
  return `${NAMESPACE}.${scope(userId, 'anonymous')}.${scope(libraryId, 'all')}`
}

/** Reads this user's navigation state for one library scope in the current tab. */
export function readHomeState(userId, libraryId) {
  try {
    const raw = sessionStorage.getItem(keyFor(userId, libraryId))
    if (raw === null) return null
    const state = JSON.parse(raw)
    const expectedLibrary = libraryId ?? null
    if (
      state?.libraryId !== expectedLibrary ||
      !Number.isInteger(state.page) ||
      state.page < 0 ||
      !Number.isFinite(state.scrollY) ||
      state.scrollY < 0
    ) {
      return null
    }
    return { libraryId: expectedLibrary, page: state.page, scrollY: state.scrollY }
  } catch {
    return null
  }
}

/** Stores navigation only; search text and results never enter this module. */
export function writeHomeState(userId, libraryId, { page, scrollY }) {
  const state = {
    libraryId: libraryId ?? null,
    page: Number.isInteger(page) && page >= 0 ? page : 0,
    scrollY: Number.isFinite(scrollY) && scrollY >= 0 ? scrollY : 0,
  }
  try {
    sessionStorage.setItem(keyFor(userId, libraryId), JSON.stringify(state))
  } catch {
    // Losing navigation state is preferable to losing the home screen.
  }
}
