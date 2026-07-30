import '@testing-library/jest-dom/vitest'
import { waitLocale } from 'svelte-i18n'
import { setupI18n } from '../src/lib/i18n.js'

// Tests assert on keys, roles and accessible names rather than on copy, but the
// catalog still has to be initialised or every rendered component would show a
// raw key path and the assertions would pass for the wrong reason.
setupI18n({ navigatorLanguages: ['ko'], storage: null })
await waitLocale()
