import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Resolved from the Vitest root rather than from `import.meta.url`: under Vite's
// transform this module's URL is not a `file:` URL, so both `fileURLToPath` and
// `new URL(...).pathname` give the wrong answer — the latter silently, as a path
// like "/src" that then fails to exist.
const SOURCE_ROOT = join(process.cwd(), 'src')

function filesUnder(directory, extension) {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) return filesUnder(path, extension)
    return path.endsWith(extension) ? [path] : []
  })
}

const COLOUR = /#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/gi

describe('design tokens', () => {
  it('are the only source of colour in components', () => {
    // The base this UI grew from defined four variables and hard-coded a dozen
    // more values inline across components. Those literals could not be themed or
    // contrast-checked, and this is what stops them coming back.
    const offenders = []
    for (const file of filesUnder(SOURCE_ROOT, '.svelte')) {
      const source = readFileSync(file, 'utf8')
      const styles = [...source.matchAll(/<style>([\s\S]*?)<\/style>/g)].map((match) => match[1])
      for (const block of styles) {
        for (const found of block.match(COLOUR) ?? []) {
          offenders.push(`${file.replace(SOURCE_ROOT, 'src')}: ${found}`)
        }
      }
    }
    expect(offenders, 'use a token from styles/tokens.css instead').toEqual([])
  })

  it('declare every variable the components reference', () => {
    // A misspelled var() silently resolves to nothing, so the element renders with
    // no background or no colour at all rather than failing visibly.
    const tokens = readFileSync(join(SOURCE_ROOT, 'styles/tokens.css'), 'utf8')
    const declared = new Set([...tokens.matchAll(/^\s*(--[a-z0-9-]+):/gim)].map((m) => m[1]))

    const missing = new Set()
    for (const file of [...filesUnder(SOURCE_ROOT, '.svelte'), join(SOURCE_ROOT, 'styles/global.css')]) {
      const source = readFileSync(file, 'utf8')
      // A property the file sets itself is component-local, not a design token — the
      // reader's column width is set inline per instance and has no business in
      // tokens.css. It still has to be declared *somewhere*, which is what catches a
      // typo: a misspelled name is declared neither here nor there.
      const local = new Set([...source.matchAll(/(--[a-z0-9-]+)\s*:/gi)].map((match) => match[1]))
      for (const [, name] of source.matchAll(/var\((--[a-z0-9-]+)/gi)) {
        if (declared.has(name) || local.has(name)) continue
        missing.add(`${file.replace(SOURCE_ROOT, 'src')}: ${name}`)
      }
    }
    expect([...missing]).toEqual([])
  })

  it('keeps the touch target at the accessible minimum', () => {
    const tokens = readFileSync(join(SOURCE_ROOT, 'styles/tokens.css'), 'utf8')
    const touch = tokens.match(/--touch-target:\s*(\d+)px/)
    expect(Number(touch[1])).toBeGreaterThanOrEqual(44)

    // Admin table rows are deliberately denser than the touch target. This pins
    // that it is a decision with a floor, not a value that drifts downward.
    const row = tokens.match(/--table-row-height:\s*(\d+)px/)
    expect(Number(row[1])).toBeGreaterThanOrEqual(32)
    expect(Number(row[1])).toBeLessThan(Number(touch[1]))
  })
})
