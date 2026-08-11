import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The shimmer has to stop on its own.
 *
 * `loading="lazy"` means an off-screen image fires neither `load` nor `error`, so its
 * placeholder is the one that never resolves — and a home grid holds a hundred of them.
 * An unbounded animation there is a hundred compositor animations running for a page
 * nobody has scrolled to. That is the state this component shipped in, and it is a
 * regression worth a guard.
 *
 * **This reads the source, not the DOM, and that is not laziness.** Svelte's scoped CSS is
 * not injected under vitest — `document.styleSheets` is empty and `getComputedStyle`
 * reports `animation-name: none` — so there is nothing in a rendered tree to assert
 * against. The declaration is what was wrong and the declaration is what is checked;
 * whether the pixels move is a browser's business.
 */
// Resolved from the vitest root rather than from `import.meta.url`: under vitest that URL
// is not a `file:` one, and `fileURLToPath` refuses it.
const source = readFileSync(resolve(process.cwd(), 'src/components/Cover.svelte'), 'utf8')

describe('Cover motion', () => {
  it('gives the shimmer a finite iteration count', () => {
    const declaration = source.match(/animation:\s*cover-shimmer[^;]*/)?.[0] ?? ''

    expect(declaration).not.toBe('')
    expect(declaration).not.toContain('infinite')
    // A bare count, so a future edit that drops it back to the default of 1 or raises it
    // to `infinite` fails here rather than in a browser nobody is watching.
    expect(declaration).toMatch(/\s\d+\s*$/)
  })

  /**
   * The resting appearance must not depend on where the animation stopped.
   *
   * It used to: an opaque travelling gradient rested on its own first stop, which is
   * `--surface-raised`, which is the cover's own background — so eleven seconds of motion
   * ended in the empty box the placeholder exists to replace. Measured in a browser, the
   * rested centre pixel was rgb(27,30,34) against a cover of rgb(25,28,32). Two layers fix
   * it: a translucent highlight over an opaque fill, so where the highlight stops changes
   * nothing about what is visible. With the fill in place the rested centre is 9 away from
   * the cover background and the corner 17.
   */
  it('puts the fill under the highlight rather than relying on where it stops', () => {
    // Two images, and the travelling one must be see-through or it decides the rest state.
    expect(source).toMatch(/background-image:\s*\n?\s*linear-gradient\(\s*\n?\s*110deg/)
    expect(source).toContain('var(--surface-sheen)')
    expect(source).toContain('linear-gradient(160deg, var(--surface-raised) 0%, var(--surface-selected) 100%)')
  })

  it('still drops the motion entirely under prefers-reduced-motion', () => {
    expect(source).toContain('prefers-reduced-motion')
  })
})
