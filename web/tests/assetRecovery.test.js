import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'

const source = readFileSync(join(process.cwd(), 'index.html'), 'utf8')
  .match(/<script id="xoboro-asset-recovery">([\s\S]*?)<\/script>/)[1]

function bootstrap({ href = 'https://reader.example.invalid/xoboro/#/read/item-1',
  entry = '/xoboro/assets/index-previous.js', values = new Map(), blocked = false } = {}) {
  const listeners = new Map()
  const location = { href, replace: vi.fn() }
  const window = { location, history: { state: { navigation: 1 }, replaceState: (_state, _title, url) => (location.href = url) },
    addEventListener: (name, handler) => listeners.set(name, handler) }
  const storage = {
    getItem: (key) => { if (blocked) throw new Error('Storage unavailable'); return values.get(key) ?? null },
    setItem: (key, value) => { if (blocked) throw new Error('Storage unavailable'); values.set(key, value) },
  }
  new Function('window', 'document', 'sessionStorage', source)(window,
    { querySelector: () => ({ src: new URL(entry, href).href }) }, storage)
  return { location, listeners, values }
}

describe('deployment asset recovery', () => {
  it('recovers a failed boot module before the application can mount', () => {
    const app = bootstrap()
    app.listeners.get('error')({ target: { tagName: 'SCRIPT', src: '/xoboro/assets/index-previous.js' } })
    const destination = new URL(app.location.replace.mock.calls[0][0])
    expect(destination.pathname).toBe('/xoboro/')
    expect(destination.hash).toBe('#/read/item-1')
    expect(destination.searchParams.get('_xoboro_recover')).toBe('/xoboro/assets/index-previous.js')
  })

  it('recovers lazy import failures once while preserving other query parameters', () => {
    const app = bootstrap({ href: 'https://reader.example.invalid/xoboro/?theme=dark#/series/series-1' })
    const event = { preventDefault: vi.fn() }
    app.listeners.get('vite:preloadError')(event)
    app.listeners.get('vite:preloadError')(event)
    expect(app.location.replace).toHaveBeenCalledTimes(1)
    expect(new URL(app.location.replace.mock.calls[0][0]).searchParams.get('theme')).toBe('dark')
    expect(event.preventDefault).toHaveBeenCalledTimes(1)
  })

  it('does not loop on the same failing build but permits a newer entry bundle', () => {
    const first = bootstrap()
    first.listeners.get('vite:preloadError')({ preventDefault() {} })
    const second = bootstrap({ values: first.values })
    second.listeners.get('vite:preloadError')({ preventDefault() {} })
    expect(second.location.replace).not.toHaveBeenCalled()
    const fresh = bootstrap({ values: first.values, entry: '/xoboro/assets/index-current1.js' })
    fresh.listeners.get('vite:preloadError')({ preventDefault() {} })
    expect(fresh.location.replace).toHaveBeenCalledTimes(1)
  })

  it('bounds retries without storage and removes its marker from the recovered address', () => {
    const first = bootstrap({ blocked: true })
    first.listeners.get('vite:preloadError')({ preventDefault() {} })
    const second = bootstrap({ blocked: true, href: first.location.replace.mock.calls[0][0] })
    expect(new URL(second.location.href).searchParams.has('_xoboro_recover')).toBe(false)
    second.listeners.get('vite:preloadError')({ preventDefault() {} })
    expect(second.location.replace).not.toHaveBeenCalled()
  })

  it('does not reload for runtime exceptions, cover failures, or external analytics', () => {
    const app = bootstrap()
    for (const target of [{}, { tagName: 'IMG', src: '/xoboro/assets/cover-previous.png' },
      { tagName: 'SCRIPT', src: 'https://other.example.invalid/assets/analytics.js' }]) {
      app.listeners.get('error')({ target })
    }
    expect(app.location.replace).not.toHaveBeenCalled()
  })
})
