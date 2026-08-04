import { render, screen } from '@testing-library/svelte'
import { beforeEach, describe, expect, it } from 'vitest'
import { applyLocale } from '../src/lib/i18n.js'
import SeriesMetadataPanel from '../src/catalog/SeriesMetadataPanel.svelte'

/**
 * What a series page says about a series.
 *
 * The screen used to render one field — the summary — while the route returns status,
 * publisher, language, age rating, genres, tags, a total book count and links. Every
 * field the sidecar importers had gathered arrived and was dropped at the last step,
 * which from a reader's seat is indistinguishable from an importer that never ran.
 */
function metadata(overrides = {}) {
  return {
    series: {
      id: 's1',
      title: 'Sample Series',
      metadata: {
        status: 'ONGOING',
        title: 'Sample Series',
        summary: 'A synthetic summary.',
        publisher: 'Sample Publisher',
        language: 'ko',
        ageRating: 15,
        genres: ['Action'],
        tags: ['Webtoon'],
        totalBookCount: 42,
        links: [{ label: 'Source', url: 'https://example.invalid/series' }],
        ...overrides,
      },
    },
  }
}

describe('series metadata panel', () => {
  // Pinned rather than assumed. The shipped default is Korean, so asserting on English
  // strings without setting this passes or fails on a setting these tests do not own.
  beforeEach(() => applyLocale('en'))

  it('shows the fields the importers filled in', () => {
    render(SeriesMetadataPanel, metadata())

    expect(screen.getByTestId('series-summary').textContent).toContain('A synthetic summary.')
    const facts = screen.getByTestId('series-facts').textContent
    expect(facts).toContain('Sample Publisher')
    expect(facts).toContain('ko')
    // Status is a domain enum, so it must be translated rather than printed raw — a
    // reader should not be shown "ONGOING".
    expect(facts).toContain('Ongoing')
    expect(facts).not.toContain('ONGOING')
    expect(facts).toContain('15+')
    expect(facts).toContain('42')
    expect(screen.getByTestId('series-genres').textContent).toContain('Action')
    expect(screen.getByTestId('series-tags').textContent).toContain('Webtoon')
  })

  it('omits a field rather than showing it blank', () => {
    // Their own series.json carries no publisher or age rating, so this is the normal
    // case, not an edge one. Eight labels with a dash beside each reads as a broken
    // page; an absent row is the honest rendering of an absent value.
    render(
      SeriesMetadataPanel,
      metadata({
        publisher: '',
        language: '',
        ageRating: null,
        genres: [],
        tags: [],
        links: [],
      }),
    )

    const facts = screen.getByTestId('series-facts').textContent
    expect(facts).toContain('Ongoing')
    expect(facts).toContain('42')
    expect(screen.queryByTestId('series-genres')).toBeNull()
    expect(screen.queryByTestId('series-tags')).toBeNull()
    expect(screen.queryByTestId('series-links')).toBeNull()
  })

  it('renders nothing at all when a series carries no metadata', () => {
    // A series indexed but never refreshed. The page above it still has to work.
    render(SeriesMetadataPanel, { series: { id: 's1', title: 'Sample Series' } })

    expect(screen.queryByTestId('series-summary')).toBeNull()
    expect(screen.queryByTestId('series-facts')).toBeNull()
  })

  it('marks outbound links noopener, because their URLs come from library files', () => {
    render(SeriesMetadataPanel, metadata())

    const link = screen.getByTestId('series-links').querySelector('a')
    expect(link.getAttribute('rel')).toContain('noopener')
    expect(link.getAttribute('href')).toBe('https://example.invalid/series')
  })
})
