import { render, screen } from '@testing-library/svelte'
import { beforeEach, describe, expect, it } from 'vitest'
import { applyLocale } from '../src/lib/i18n.js'
import MediaShelf from '../src/components/MediaShelf.svelte'

/**
 * A shelf card carries no words about progress — there is no room for them beside a
 * 120px cover — so unlike the series list, the bar is the only thing that says how far a
 * reader got. It has to be announced rather than hidden.
 */
describe('MediaShelf progress', () => {
  beforeEach(() => applyLocale('en'))

  const started = {
    id: 'm1',
    title: 'Synthetic Chapter',
    seriesTitle: 'Synthetic Series',
    media: { pageCount: 10 },
    progress: { page: 5, completed: false },
  }

  it('announces how far a started item has been read', () => {
    render(MediaShelf, { title: 'Keep reading', items: [started], kind: 'mediaItem' })

    const bar = screen.getByTestId('shelf-progress')
    expect(bar.getAttribute('data-percent')).toBe('50')
    expect(bar.getAttribute('role')).toBe('progressbar')
    expect(bar.getAttribute('aria-valuenow')).toBe('50')
    expect(bar.getAttribute('aria-label')).toContain('50')
  })

  it('draws no bar for an item nobody has opened', () => {
    render(MediaShelf, {
      title: 'New',
      items: [{ ...started, progress: null }],
      kind: 'mediaItem',
    })

    expect(screen.queryByTestId('shelf-progress')).toBeNull()
  })

  it('draws no bar on a series card, which has no progress of its own', () => {
    render(MediaShelf, { title: 'Recent', items: [{ id: 's1', title: 'S' }], kind: 'series' })

    expect(screen.queryByTestId('shelf-progress')).toBeNull()
  })
})
