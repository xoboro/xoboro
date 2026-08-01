import { request } from '../http.js'
import { readFacet } from './metadata.js'

/**
 * The reader's search, filter and sort layer over the two catalog listings.
 *
 * ## Why the two collections are separate scopes rather than one merged result
 *
 * `/series` and `/media-items` accept **different** filters, and the difference is
 * silent: a series filter sent to the media-item listing is neither applied nor
 * rejected. Verified against a running server — `/media-items?publisher=x`,
 * `?genre=x`, `?tag=x`, `?language=x` and `?oneShot=true` each returned the entire
 * catalog unchanged. A merged surface offering one filter bar over both collections
 * would therefore show a filter that works on half the results and is discarded for
 * the other half, with nothing on screen to say so.
 *
 * So each scope owns its own query builder below. That is structural rather than
 * documentary: there is no code path that can put `genre` into a `/media-items`
 * request, so the mistake cannot be made by editing a shared parameter list later.
 *
 * The sorts differ per collection too, and there the server *does* refuse — an
 * unknown field is `400 invalid_query`. Refused here as well, before the request, so
 * a wrong field is a visible programming error rather than a failed search the reader
 * has to interpret.
 */

/** The default page size for a result grid. Within the server's 1..200 bound. */
export const DEFAULT_PAGE_SIZE = 24

/** The largest `size` the listings accept. Above it they answer `400 invalid_query`. */
export const MAX_PAGE_SIZE = 200

const DIRECTIONS = Object.freeze(['asc', 'desc'])

/**
 * The two searchable collections, each with the sorts and the query it accepts.
 *
 * `defaultSort` matches what the route already orders by when no `sort` is sent
 * (`titleSort` for series, `seriesTitle` for media items), so the first result page a
 * reader sees is the same whether or not the sort control has been touched.
 */
export const SCOPES = Object.freeze({
  series: Object.freeze({
    path: '/series',
    sorts: Object.freeze([
      'title',
      'createdAt',
      'updatedAt',
      'sourceModifiedAt',
      'lastReadAt',
      'mediaItemCount',
    ]),
    defaultSort: Object.freeze({ field: 'title', direction: 'asc' }),
    query: seriesQuery,
  }),
  mediaItems: Object.freeze({
    path: '/media-items',
    sorts: Object.freeze([
      'title',
      'seriesTitle',
      'number',
      'createdAt',
      'updatedAt',
      'sourceModifiedAt',
      'fileSize',
      'lastReadAt',
      'seriesLastReadAt',
    ]),
    defaultSort: Object.freeze({ field: 'seriesTitle', direction: 'asc' }),
    query: mediaItemQuery,
  }),
})

export const SCOPE_NAMES = Object.freeze(Object.keys(SCOPES))

/**
 * The series filters whose choices come from a facet, and which facet supplies each.
 *
 * The two names differ for a reason worth keeping: the listing parameter is `tag`
 * while the facet that enumerates a series' tags is `seriesTag`. Pairing them here
 * once is what stops a filter group from being populated out of `bookTag` — a facet
 * with real values in it and no listing parameter that accepts them, so the resulting
 * filter would return nothing and look like an empty library.
 */
export const SERIES_FACET_FILTERS = Object.freeze([
  Object.freeze({ parameter: 'genre', facet: 'genre' }),
  Object.freeze({ parameter: 'tag', facet: 'seriesTag' }),
  Object.freeze({ parameter: 'publisher', facet: 'publisher' }),
  Object.freeze({ parameter: 'language', facet: 'language' }),
])

/**
 * Facets the server will enumerate that **no** listing filters by.
 *
 * `/facets` answers eight names. Four of them describe metadata neither
 * `SeriesCatalogQuery` nor `BookCatalogQuery` reads, so offering them as filters
 * would put a control on screen that changes the request and not the results.
 * Confirmed against a running server: `/series?ageRating=None`, `?releaseYear=1999`
 * and `?sharingLabel=x` each returned all ten series.
 *
 * Named rather than merely omitted, so a later reading of the facet list can see that
 * the gap was measured instead of overlooked.
 */
export const UNFILTERABLE_FACETS = Object.freeze([
  'bookTag',
  'ageRating',
  'sharingLabel',
  'releaseYear',
])

/**
 * How the `oneShot` filter is offered.
 *
 * Three states, not a checkbox: `oneShot` is a tri-state on the wire — absent means
 * "either", and the server rejects anything that is not `true` or `false`. A checkbox
 * can only express two of the three, and the one it loses is the default.
 */
export const ONE_SHOT_CHOICES = Object.freeze(['any', 'only', 'exclude'])

const ONE_SHOT_VALUES = Object.freeze({ any: null, only: true, exclude: false })

/**
 * Runs one search.
 *
 * @param {'series'|'mediaItems'} scope which collection to search.
 * @param {object} criteria the reader's query, filters, sort and page.
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<object>} the page envelope: `items`, `page`, `size`,
 *   `totalItems`, `totalPages`, `hasPrevious`, `hasNext`.
 */
export function searchCatalog(scope, criteria = {}, { signal } = {}) {
  const definition = SCOPES[scope]
  if (!definition) throw new Error(`unknown search scope: ${scope}`)
  return request(definition.path, { query: definition.query(scope, criteria), signal })
}

/**
 * `query`, `libraryId`, the four facet filters, `oneShot`, paging and a series sort.
 *
 * Deliberately not `trashed`. It selects trashed entries *instead of* live ones
 * rather than in addition to them, so a reader who found it would be looking at a
 * list of things they cannot open. The console's Trash screen is where that listing
 * belongs and where it already is.
 */
function seriesQuery(scope, criteria) {
  const { genre = [], tag = [], publisher = [], language = [], oneShot = 'any' } = criteria
  if (!ONE_SHOT_CHOICES.includes(oneShot)) throw new Error(`unknown oneShot choice: ${oneShot}`)
  return {
    ...commonQuery(scope, criteria),
    genre: [...genre],
    tag: [...tag],
    publisher: [...publisher],
    language: [...language],
    oneShot: ONE_SHOT_VALUES[oneShot],
  }
}

/**
 * `query`, `libraryId`, `onDeck`, `keepReading`, paging and a media-item sort.
 *
 * `onDeck` and `keepReading` are the reader's own progress, which is why they are
 * here and not among the series filters: the server reads them from
 * `BookCatalogQuery` only.
 *
 * Each is sent only when set. `onDeck=false` is accepted and means the same as
 * omitting it, but the two together are mutually exclusive — a started-and-unfinished
 * item is by definition not one the reader has never opened — so the pair returns
 * nothing, which is the truthful answer to a contradictory question and is left to
 * the reader to see rather than silently repaired here.
 */
function mediaItemQuery(scope, criteria) {
  const { onDeck = false, keepReading = false } = criteria
  return {
    ...commonQuery(scope, criteria),
    onDeck: onDeck ? true : null,
    keepReading: keepReading ? true : null,
  }
}

/** What both listings accept identically: the text query, libraries, paging, sort. */
function commonQuery(scope, criteria) {
  const { query = '', libraryId = [], page = 0, size = DEFAULT_PAGE_SIZE, sort = null } = criteria
  if (!Number.isInteger(page) || page < 0) throw new Error('page must be a non-negative integer')
  if (!Number.isInteger(size) || size < 1 || size > MAX_PAGE_SIZE) {
    throw new Error(`size must be between 1 and ${MAX_PAGE_SIZE}`)
  }
  const trimmed = String(query).trim()
  return {
    // Omitted when blank rather than sent empty. The server trims and discards an
    // empty value itself, so both mean "everything"; not sending it keeps the
    // request honest about what was asked, which is what a server log has to read.
    query: trimmed === '' ? null : trimmed,
    libraryId: [...libraryId],
    page,
    size,
    sort: sortParameter(scope, sort),
  }
}

/**
 * Renders a sort as the wire's `field[,direction]`.
 *
 * The comma survives `URLSearchParams` as `%2C` and the server percent-decodes
 * before splitting, which was checked against a running server rather than assumed:
 * `sort=title%2Cdesc` orders descending.
 */
function sortParameter(scope, sort) {
  if (!sort) return null
  const { field, direction = 'asc' } = sort
  if (!SCOPES[scope].sorts.includes(field)) {
    throw new Error(`${scope} cannot be sorted by ${field}`)
  }
  if (!DIRECTIONS.includes(direction)) throw new Error(`unknown sort direction: ${direction}`)
  return `${field},${direction}`
}

/**
 * Reads the choices for every facet-backed series filter.
 *
 * Settled rather than all-or-nothing, and the failures are **returned** rather than
 * thrown or dropped. One unreachable facet must not remove the three filter groups
 * that answered, and it must not leave a filter quietly missing either: a group with
 * no choices renders as nothing at all, which reads as "this library has no genres"
 * when it actually means "nobody knows". The caller says which it is.
 *
 * Asked without a `libraryId` even when the reader has narrowed to one library. The
 * facets accept the parameter, but re-reading them per selection would remove choices
 * the reader had already ticked — a filter disappearing because of a different filter.
 *
 * @returns {Promise<{choices: Record<string, string[]>, failed: string[]}>} choices
 *   keyed by **listing parameter**, and the facet names that could not be read.
 */
export async function readSeriesFilterChoices() {
  const settled = await Promise.allSettled(
    SERIES_FACET_FILTERS.map(({ facet }) => readFacet(facet)),
  )
  const choices = {}
  const failed = []
  SERIES_FACET_FILTERS.forEach(({ parameter, facet }, index) => {
    const result = settled[index]
    if (result.status === 'fulfilled') choices[parameter] = result.value ?? []
    else failed.push(facet)
  })
  return { choices, failed }
}
