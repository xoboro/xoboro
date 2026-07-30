import { addMessages, init, locale, _ } from 'svelte-i18n'
import en from './messages/en.js'
import ko from './messages/ko.js'

export const LOCALES = Object.freeze(['ko', 'en'])
const FALLBACK = 'ko'
const STORAGE_KEY = 'xoboro.locale'

addMessages('ko', ko)
addMessages('en', en)

/**
 * Picks a starting locale.
 *
 * A stored choice wins over the browser's preference, because someone who
 * switched language explicitly meant it. An unrecognised stored value is ignored
 * rather than trusted — it is user-writable storage.
 */
function initialLocale(navigatorLanguages = [], storage = globalThis.localStorage) {
  const stored = readStored(storage)
  if (stored) return stored
  for (const language of navigatorLanguages) {
    const base = String(language).split('-')[0]
    if (LOCALES.includes(base)) return base
  }
  return FALLBACK
}

function readStored(storage) {
  try {
    const value = storage?.getItem(STORAGE_KEY)
    return LOCALES.includes(value) ? value : null
  } catch {
    // Storage can throw outright in a private browsing context. A missing
    // preference is not an error worth surfacing.
    return null
  }
}

export function setupI18n({ navigatorLanguages = [], storage = globalThis.localStorage } = {}) {
  init({
    fallbackLocale: FALLBACK,
    initialLocale: initialLocale(navigatorLanguages, storage),
  })
}

/**
 * Switches locale and remembers it.
 *
 * An unknown value is rejected rather than applied, so a bad call renders the
 * fallback language instead of a screen full of raw key paths.
 */
export function applyLocale(next, storage = globalThis.localStorage) {
  if (!LOCALES.includes(next)) return false
  locale.set(next)
  try {
    storage?.setItem(STORAGE_KEY, next)
  } catch {
    // Not being able to remember the choice is not a reason to refuse it.
  }
  return true
}

export { _, locale }
