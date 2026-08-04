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

const TOKEN_SOURCE = readFileSync(join(SOURCE_ROOT, 'styles/tokens.css'), 'utf8')

/** The value of a custom property, as authored. */
function token(name) {
  const found = TOKEN_SOURCE.match(new RegExp(`${name}:\\s*([^;]+);`))
  if (!found) throw new Error(`token not declared: ${name}`)
  return found[1].trim()
}

function components(hex) {
  const digits = hex.replace('#', '')
  return [0, 2, 4].map((at) => parseInt(digits.slice(at, at + 2), 16))
}

function luminance(hex) {
  const [r, g, b] = components(hex).map((value) => {
    const scaled = value / 255
    return scaled <= 0.04045 ? scaled / 12.92 : ((scaled + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio between two tokens, read by name so it cannot drift. */
function ratio(foreground, background) {
  const [a, b] = [luminance(token(foreground)), luminance(token(background))]
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

/** A token's hue in degrees, for judging how far apart two intent colours sit. */
function hue(name) {
  const [r, g, b] = components(token(name)).map((value) => value / 255)
  const high = Math.max(r, g, b)
  const low = Math.min(r, g, b)
  if (high === low) return 0
  const span = high - low
  const sixths =
    high === r ? ((g - b) / span) % 6 : high === g ? (b - r) / span + 2 : (r - g) / span + 4
  return (((sixths * 60) % 360) + 360) % 360
}

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

  it('keeps every text pairing above the AA ratio it needs', () => {
    // The palette is sampled from the product mark, which makes it a brand decision
    // as much as a legibility one — and brand decisions get revisited. A ratio
    // checked once by hand degrades silently the next time a value is nudged, and
    // neither the build nor a screenshot catches it.
    //
    // 4.5:1 for text; 3:1 for a control's fill judged against the page behind it.
    expect(ratio('--text', '--surface')).toBeGreaterThanOrEqual(4.5)
    expect(ratio('--text', '--surface-raised')).toBeGreaterThanOrEqual(4.5)
    expect(ratio('--text', '--surface-control')).toBeGreaterThanOrEqual(4.5)
    expect(ratio('--text-secondary', '--surface')).toBeGreaterThanOrEqual(4.5)
    expect(ratio('--text-secondary', '--surface-raised')).toBeGreaterThanOrEqual(4.5)

    expect(ratio('--accent', '--surface')).toBeGreaterThanOrEqual(3)
    expect(ratio('--accent-contrast', '--accent')).toBeGreaterThanOrEqual(4.5)
    expect(ratio('--accent-text', '--surface')).toBeGreaterThanOrEqual(4.5)
    expect(ratio('--accent-text', '--surface-raised')).toBeGreaterThanOrEqual(4.5)

    for (const intent of ['--danger', '--warning', '--success']) {
      expect(ratio(intent, '--surface'), intent).toBeGreaterThanOrEqual(4.5)
    }
    expect(ratio('--danger-contrast', '--danger')).toBeGreaterThanOrEqual(4.5)
  })

  it('keeps the quietest text above AA rather than merely above the large-text floor', () => {
    // This assertion was originally written as a ceiling, on the belief that muted text
    // is allowed to fail AA because nothing important is said in it. The value that
    // shipped clears 4.5:1, so the ceiling was asserting a worse UI than the one that
    // exists — the floor is what actually protects a reader.
    expect(ratio('--text-muted', '--surface')).toBeGreaterThanOrEqual(4.5)
    // Still visibly quieter than a label, which is the whole point of having both.
    expect(ratio('--text-muted', '--surface')).toBeLessThan(
      ratio('--text-secondary', '--surface'),
    )
  })

  it('separates warning from the accent that now owns amber', () => {
    // A warning at the accent's hue reads as a highlight rather than a caution. The
    // gap is measured because "looks different enough" is exactly the judgement that
    // slips when a value is nudged.
    expect(hue('--warning')).toBeLessThan(hue('--accent') - 10)
    expect(hue('--warning')).toBeGreaterThan(hue('--danger') + 10)
  })

  it('keeps borders visible against the surfaces they divide', () => {
    // Dark themes lose their borders first, and once they go every control starts to
    // look like flat text on a panel.
    expect(ratio('--line', '--surface')).toBeGreaterThan(1.15)
    expect(ratio('--line-strong', '--surface-raised')).toBeGreaterThan(1.15)
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
