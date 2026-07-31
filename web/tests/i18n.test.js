import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { KNOWN_CODES } from '../src/lib/errors.js'
import { LOCALES, applyLocale } from '../src/lib/i18n.js'
import en from '../src/lib/messages/en.js'
import ko from '../src/lib/messages/ko.js'

function flatten(object, prefix = '') {
  return Object.entries(object).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return value && typeof value === 'object' ? flatten(value, path) : [path]
  })
}

describe('catalog parity', () => {
  it('carries the same keys in both languages', () => {
    // A drifted catalog renders a raw key path to a user in one language and
    // nowhere else, which nobody notices until a customer does.
    const korean = flatten(ko).sort()
    const english = flatten(en).sort()
    expect(english).toEqual(korean)
  })

  it('has no empty strings', () => {
    // An empty string translates to a blank label rather than a visible defect.
    for (const [name, catalog] of [['ko', ko], ['en', en]]) {
      for (const path of flatten(catalog)) {
        const value = path.split('.').reduce((node, key) => node[key], catalog)
        expect(value.trim(), `${name}.${path} is blank`).not.toBe('')
      }
    }
  })
})

describe('error coverage', () => {
  it('names every code the client understands', () => {
    const korean = new Set(flatten(ko))
    const english = new Set(flatten(en))
    for (const code of KNOWN_CODES) {
      expect(korean.has(`errors.${code}`), `ko errors.${code}`).toBe(true)
      expect(english.has(`errors.${code}`), `en errors.${code}`).toBe(true)
    }
  })
})

describe('applyLocale', () => {
  it('accepts the supported locales and refuses anything else', () => {
    // An unknown locale must not be applied: svelte-i18n would then have no
    // dictionary and every screen would render key paths.
    for (const supported of LOCALES) {
      expect(applyLocale(supported, null)).toBe(true)
    }
    expect(applyLocale('fr', null)).toBe(false)
    expect(applyLocale(undefined, null)).toBe(false)
  })

  it('still switches when storage refuses to remember the choice', () => {
    // localStorage throws outright in some private-browsing modes. Not being able
    // to remember a preference is no reason to refuse it.
    const hostile = {
      getItem: () => {
        throw new Error('denied')
      },
      setItem: () => {
        throw new Error('denied')
      },
    }
    expect(applyLocale('en', hostile)).toBe(true)
  })
})

describe('referenced keys', () => {
  /**
   * Every `$_('…')` in a component must exist in the catalog.
   *
   * This is the assertion whose absence let `common.settings` and `common.list` ship
   * referenced but undeclared: `svelte-i18n` renders the key path itself when a key is
   * missing, so the reader's settings button was labelled "common.settings" — visible
   * copy and an accessible name, both wrong, and nothing failed.
   *
   * Parity between `ko` and `en` did not catch it, because the key was missing from
   * both. Reading the components is the only way to know what is actually asked for.
   */
  const componentFiles = () => {
    const walk = (directory) =>
      readdirSync(directory).flatMap((entry) => {
        const path = join(directory, entry)
        return statSync(path).isDirectory()
          ? walk(path)
          : path.endsWith('.svelte')
            ? [path]
            : []
      })
    return walk(join(process.cwd(), 'src'))
  }

  it('are all declared in the catalog', () => {
    const declared = new Set(flatten(ko))
    const offenders = []

    for (const file of componentFiles()) {
      const source = readFileSync(file, 'utf8')
      // Only literal keys can be checked. A computed one - $_(`a.${b}`) - is skipped
      // deliberately rather than guessed at, and its prefix is asserted separately by
      // the screen that builds it.
      for (const [, key] of source.matchAll(/\$_\(\s*'([A-Za-z][\w.]*)'/g)) {
        if (!declared.has(key)) offenders.push(`${file.split('/src/')[1]}: ${key}`)
      }
    }

    expect(offenders).toEqual([])
  })
})
