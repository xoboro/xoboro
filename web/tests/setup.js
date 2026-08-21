import '@testing-library/jest-dom/vitest'
import { waitLocale } from 'svelte-i18n'
import { setupI18n } from '../src/lib/i18n.js'

// Node 26 reserves a global localStorage getter that returns undefined unless its
// file-backed implementation is configured. Vitest therefore sees the name as already
// occupied and skips jsdom's storage while populating browser globals. Resolve it through
// the active jsdom instance so each test environment receives its own in-memory store.
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  enumerable: true,
  get: () => globalThis.jsdom?.window.localStorage,
})

// Tests assert on keys, roles and accessible names rather than on copy, but the
// catalog still has to be initialised or every rendered component would show a
// raw key path and the assertions would pass for the wrong reason.
setupI18n({ navigatorLanguages: ['ko'], storage: null })
await waitLocale()

// jsdom implements neither of these, and the reader calls both while restoring a saved
// position. Without the stubs the suite reported "224 passed" while exiting 1 on two
// unhandled errors — a green summary over a real crash, which is worse than a red one.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView() {}
}
if (!globalThis.requestAnimationFrame) {
  globalThis.requestAnimationFrame = (fn) => setTimeout(() => fn(Date.now()), 0)
}
