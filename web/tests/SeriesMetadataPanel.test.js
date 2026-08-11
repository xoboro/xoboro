import { render, screen } from '@testing-library/svelte'
import { beforeEach, describe, expect, it } from 'vitest'
import { applyLocale } from '../src/lib/i18n.js'
import SeriesMetadataPanel from '../src/catalog/SeriesMetadataPanel.svelte'

/**
 * What a series page says about a series.
 *
 * These fixtures used to wrap every field in a `metadata` object and set a
 * `totalBookCount`, which is the shape `PATCH /series/{id}/metadata` *answers* with.
 * The screen reads `GET /series/{id}`, which carries the same fields **flat** and has
 * no locks and no `totalBookCount` — there is no `GET .../metadata` to read the other
 * shape from. So the suite agreed with the component, the component agreed with a
 * response nobody fetches, and neither agreed with the server: `series.metadata` was
 * always `undefined` and the panel rendered nothing at all in front of a real catalogue.
 *
 * The fixture below is `XoboroSeriesResponse` field for field. Getting this wrong is
 * invisible in jsdom and total in production, so the shape is the assertion.
 */
function series(overrides = {}) {
  return {
    series: {
      id: 's1',
      libraryId: 'lib1',
      title: 'Sample Series',
      sortTitle: 'Sample Series',
      summary: 'A synthetic summary.',
      status: 'ONGOING',
      readingDirection: null,
      publisher: 'Sample Publisher',
      ageRating: 15,
      language: 'ko',
      genres: ['Action'],
      tags: ['Webtoon'],
      links: [{ label: 'Source', url: 'https://example.invalid/series' }],
      alternateTitles: [],
      authors: [{ name: 'Sample Author', role: 'writer' }],
      mediaItemCount: 42,
      expectedMediaItemCount: null,
      oneShot: false,
      deleted: false,
      createdAtMillis: 1,
      updatedAtMillis: 1,
      sourceModifiedAtMillis: 1,
      ...overrides,
    },
  }
}

describe('series metadata panel', () => {
  // Pinned rather than assumed. The shipped default is Korean, so asserting on English
  // strings without setting this passes or fails on a setting these tests do not own.
  beforeEach(() => applyLocale('en'))

  it('shows the fields the importers filled in', () => {
    render(SeriesMetadataPanel, series())

    expect(screen.getByTestId('series-summary').textContent).toContain('A synthetic summary.')
    const facts = screen.getByTestId('series-facts').textContent
    expect(facts).toContain('Sample Publisher')
    // The language is named, not printed as its tag: that is what a reader calls it.
    expect(facts).toContain('Korean')
    // Status is a domain enum, so it must be translated rather than printed raw — a
    // reader should not be shown "ONGOING".
    expect(facts).toContain('Ongoing')
    expect(facts).not.toContain('ONGOING')
    expect(facts).toContain('15+')
    expect(facts).toContain('42')
    expect(screen.getByTestId('series-genres').textContent).toContain('Action')
    expect(screen.getByTestId('series-tags').textContent).toContain('Webtoon')
  })

  /**
   * Authors are what a reader looks for first and the one field the series route did
   * not carry: they live on each media item, and the aggregation across a series
   * already exists in the read model — the native response simply dropped it.
   */
  it('shows the authors, with their roles', () => {
    render(
      SeriesMetadataPanel,
      series({
        authors: [
          { name: 'Story Writer', role: 'writer' },
          { name: 'Line Artist', role: 'penciller' },
        ],
      }),
    )

    const authors = screen.getByTestId('series-authors').textContent
    expect(authors).toContain('Story Writer')
    expect(authors).toContain('Line Artist')
    // The role is translated, not printed as the wire value.
    expect(authors).toContain('Writer')
    expect(authors).not.toContain('penciller')
  })

  it('prints an unknown role as it came rather than dropping the author', () => {
    // Roles come from a sidecar the server did not author, so the set is open. Hiding
    // an author because their role is unrecognised loses the fact that they exist.
    render(SeriesMetadataPanel, series({ authors: [{ name: 'Someone', role: 'assistant' }] }))

    const authors = screen.getByTestId('series-authors').textContent
    expect(authors).toContain('Someone')
    expect(authors).toContain('assistant')
  })

  it('omits a field rather than showing it blank', () => {
    // Their own series.json carries no publisher or age rating, so this is the normal
    // case, not an edge one. Eight labels with a dash beside each reads as a broken
    // page; an absent row is the honest rendering of an absent value.
    render(
      SeriesMetadataPanel,
      series({
        publisher: '',
        language: '',
        ageRating: null,
        genres: [],
        tags: [],
        links: [],
        authors: [],
      }),
    )

    const facts = screen.getByTestId('series-facts').textContent
    expect(facts).toContain('Ongoing')
    expect(facts).toContain('42')
    expect(screen.queryByTestId('series-genres')).toBeNull()
    expect(screen.queryByTestId('series-tags')).toBeNull()
    expect(screen.queryByTestId('series-links')).toBeNull()
    expect(screen.queryByTestId('series-authors')).toBeNull()
  })

  /**
   * The count a reader is shown is how many items are *here*, and the expected count is
   * how many the source says there should be. Reporting only the first turns a
   * part-scanned series into a complete-looking one; reporting only the second promises
   * items that cannot be opened.
   */
  it('says how many items are present, and how many are expected when that differs', () => {
    render(SeriesMetadataPanel, series({ mediaItemCount: 12, expectedMediaItemCount: 20 }))

    const facts = screen.getByTestId('series-facts').textContent
    expect(facts).toContain('12')
    expect(facts).toContain('20')
  })

  it('renders nothing at all when a series carries no fields worth showing', () => {
    // A series indexed but never refreshed. The page above it still has to work. The
    // shape is the real one — a flat series with empty strings, which is what the
    // server sends — rather than an object with no `metadata` key, which is what this
    // test used to assert and what made the broken rendering look correct.
    render(
      SeriesMetadataPanel,
      series({
        summary: '',
        status: '',
        publisher: '',
        language: '',
        ageRating: null,
        genres: [],
        tags: [],
        links: [],
        authors: [],
        mediaItemCount: 0,
      }),
    )

    expect(screen.queryByTestId('series-summary')).toBeNull()
    expect(screen.queryByTestId('series-facts')).toBeNull()
  })

  /**
   * `alternateTitles` is on the series route and was dropped at the last step, the same
   * way every other field on this panel was. A series known by a second name is exactly
   * the case a reader needs the panel for.
   */
  it('shows alternate titles, labelled where the entry carries a label', () => {
    render(
      SeriesMetadataPanel,
      series({
        alternateTitles: [
          { label: 'Short', title: 'Sample Short' },
          { label: '', title: 'Sample Unlabelled' },
        ],
      }),
    )

    const titles = screen.getByTestId('series-alternate-titles').textContent
    expect(titles).toContain('Short')
    expect(titles).toContain('Sample Short')
    expect(titles).toContain('Sample Unlabelled')
  })

  it('omits the alternate titles section when there are none', () => {
    render(SeriesMetadataPanel, series({ alternateTitles: [] }))

    expect(screen.queryByTestId('series-alternate-titles')).toBeNull()
  })

  /**
   * A reader is shown a language, not a tag. `ko` is what the sidecar stores and what the
   * route sends; it is not what anyone calls the language.
   */
  it('names the language rather than printing its code', () => {
    render(SeriesMetadataPanel, series({ language: 'ko' }))

    const facts = screen.getByTestId('series-facts').textContent
    expect(facts).toContain('Korean')
    expect(facts).not.toMatch(/\bko\b/)
  })

  it('prints a language code it cannot resolve rather than dropping the row', () => {
    // Structurally invalid, so `Intl.DisplayNames` throws rather than answering. A merely
    // unfamiliar-looking tag is not the case to test with: `zxx-Qaai` is well formed and
    // Intl resolves it to "No linguistic content (Inherited)", so it would prove nothing.
    // The value comes from a library file, so the set is open. Hiding the row would lose
    // the fact that a language was recorded at all.
    render(SeriesMetadataPanel, series({ language: '!!' }))

    expect(screen.getByTestId('series-facts').textContent).toContain('!!')
  })

  it('marks outbound links noopener, because their URLs come from library files', () => {
    render(SeriesMetadataPanel, series())

    const link = screen.getByTestId('series-links').querySelector('a')
    expect(link.getAttribute('rel')).toContain('noopener')
    expect(link.getAttribute('href')).toBe('https://example.invalid/series')
  })
})
