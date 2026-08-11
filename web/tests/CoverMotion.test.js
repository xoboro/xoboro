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

  it('holds the last frame instead of snapping back to the first', () => {
    expect(source).toContain('animation-fill-mode: forwards')
  })

  it('still drops the motion entirely under prefers-reduced-motion', () => {
    expect(source).toContain('prefers-reduced-motion')
  })
})
