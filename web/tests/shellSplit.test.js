import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SOURCE_ROOT = join(process.cwd(), 'src')

/**
 * Guards the property that makes "one application, two shells" cost the reader
 * nothing: the console must be a dynamically imported chunk.
 *
 * Asserted on the source rather than on build output, because a bundle-size
 * assertion breaks on every unrelated change while saying nothing about intent. A
 * static `import AdminShell from …` in the routing table would silently pull every
 * admin screen into the entry bundle and no test that measured bytes would say why.
 */
describe('shell split', () => {
  const shell = readFileSync(join(SOURCE_ROOT, 'AppShell.svelte'), 'utf8')

  it('loads the console asynchronously', () => {
    expect(shell).toContain('asyncComponent')
    expect(shell).toMatch(/import\(['"]\.\/admin\/AdminShell\.svelte['"]\)/)
  })

  it('never statically imports the console', () => {
    // The one line that would undo the split.
    expect(shell).not.toMatch(/^\s*import\s+\w+\s+from\s+['"]\.\/admin\//m)
  })

  it('loads each reader screen as a route chunk', () => {
    for (const component of ['Home', 'Search', 'SeriesScreen', 'ReaderRoute']) {
      expect(shell).toMatch(
        new RegExp(`import\\(['\"]\\.\\/reader\\/${component}\\.svelte['\"]\\)`),
      )
    }
    expect(shell).not.toMatch(/^\s*import\s+\w+\s+from\s+['"]\.\/reader\//m)
  })

  it('keeps the reader path free of admin imports', () => {
    // A shared component pulled from admin/ into the reader would drag the chunk back
    // into the entry bundle just as effectively as a static route import.
    const reader = readFileSync(join(SOURCE_ROOT, 'reader/Home.svelte'), 'utf8')
    expect(reader).not.toContain('../admin/')
  })

  it('matches every path under the console, not only its root', () => {
    // The console does its own nested routing, so it has to keep matching beneath
    // itself; without the wildcard, /admin/libraries would fall through to the
    // not-found route.
    expect(shell).toContain("'/admin/*'")
    expect(shell).toContain("'/admin'")
  })
})
