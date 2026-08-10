import { request } from '../http.js'

/**
 * Metadata editing.
 *
 * Not administrator-gated: any caller may edit metadata for content their normal
 * catalog access can see. Missing and unauthorized content are deliberately
 * indistinguishable — both answer `404` — so the UI must not try to tell them apart.
 *
 * ## The two kinds of field, and why this module exists
 *
 * The patch endpoints have **two opposite null semantics**, and nothing in the JSON
 * distinguishes them:
 *
 * - A *patch field*: absent preserves the stored value, explicit `null` **clears** it.
 * - A *plain optional* field: absent and `null` are treated alike and both
 *   **preserve** the stored value. There is no way to clear one with `null`; a string
 *   is emptied by sending `""`.
 *
 * Worse, the split is not consistent across resources. `summary` is a patch field on
 * a media item and a plain optional on a series — the same field name with the
 * opposite meaning for `null`, depending on which thing you are editing. A form that
 * sent `null` for every emptied input would silently no-op half its fields and clear
 * the other half.
 *
 * So callers never construct the body themselves: they say what they want with
 * {@link CLEAR} and {@link buildMetadataPatch} produces the shape that means it.
 */

/** Ask for a field to be emptied. Not `null` — what `null` means depends on the field. */
export const CLEAR = Symbol('clear')

/** Series fields where an explicit `null` clears the stored value. */
export const SERIES_PATCH_FIELDS = Object.freeze([
  'readingDirection',
  'ageRating',
  'genres',
  'tags',
  'totalBookCount',
  'sharingLabels',
  'links',
  'alternateTitles',
])

/** Media-item fields where an explicit `null` clears the stored value. */
export const BOOK_PATCH_FIELDS = Object.freeze([
  'summary',
  'releaseDate',
  'authors',
  'tags',
  'isbn',
  'links',
])

/**
 * Plain optional fields that are strings, so emptying them means sending `""`.
 *
 * Listed rather than inferred from the current value: a field that happens to hold a
 * string today is not necessarily one the server will accept `""` for.
 */
const SERIES_EMPTIABLE_STRINGS = Object.freeze(['title', 'titleSort', 'summary', 'publisher', 'language'])
const BOOK_EMPTIABLE_STRINGS = Object.freeze(['title', 'number'])

const KINDS = Object.freeze({
  series: { patchFields: SERIES_PATCH_FIELDS, emptiableStrings: SERIES_EMPTIABLE_STRINGS },
  mediaItem: { patchFields: BOOK_PATCH_FIELDS, emptiableStrings: BOOK_EMPTIABLE_STRINGS },
})

/**
 * Turns edits into a request body whose nulls mean what the caller intended.
 *
 * @param {'series'|'mediaItem'} kind which resource is being patched.
 * @param {Record<string, unknown>} edits field values; use {@link CLEAR} to empty one.
 *   Fields absent from this object are absent from the body, which preserves them.
 * @returns {Record<string, unknown>}
 */
export function buildMetadataPatch(kind, edits) {
  const shape = KINDS[kind]
  if (!shape) throw new Error(`unknown metadata kind: ${kind}`)

  const body = {}
  for (const [field, value] of Object.entries(edits)) {
    if (value !== CLEAR) {
      body[field] = value
      continue
    }
    if (shape.patchFields.includes(field)) {
      body[field] = null
    } else if (shape.emptiableStrings.includes(field)) {
      // `null` here would be silently ignored, so the request would report success
      // having changed nothing.
      body[field] = ''
    } else {
      // An enum or a number with no empty value and no clearing semantics. Failing
      // loudly beats sending a `null` the server will discard while answering 200.
      throw new Error(`${kind}.${field} cannot be cleared`)
    }
  }
  return body
}

/**
 * A lock does **not** prevent editing.
 *
 * The server stores locks for the metadata-refresh pipeline to respect; they do not
 * stop a later manual patch from changing the locked field. Presenting a lock as
 * write protection would be wrong in the one direction that matters — someone would
 * trust it to hold and then be surprised when their own edit went through.
 */
export const LOCK_MEANING = 'refresh-protection'

/**
 * Reads the editable surface, which is not the one the series page reads.
 *
 * `GET /series/{id}` carries the same values flat and **no locks**, so a form seeded
 * from it cannot show which fields a refresh is allowed to overwrite. It is also the
 * shape the form used to reach for under a `metadata` key that route has never sent,
 * which left every field blank — and blank fields are how an untouched save came to
 * overwrite the stored status.
 */
export function readSeriesMetadata(seriesId) {
  return request(`/series/${seriesId}/metadata`)
}

export function patchSeriesMetadata(seriesId, edits) {
  return request(`/series/${seriesId}/metadata`, {
    method: 'PATCH',
    body: buildMetadataPatch('series', edits),
  })
}

export function patchMediaItemMetadata(mediaItemId, edits) {
  return request(`/media-items/${mediaItemId}/metadata`, {
    method: 'PATCH',
    body: buildMetadataPatch('mediaItem', edits),
  })
}

/** The bulk endpoint's hard limit. Exceeding it is rejected, not truncated. */
export const BULK_LIMIT = 200

/**
 * Applies the same edits to several media items.
 *
 * Authorization is all-or-nothing: if any identifier is missing or unauthorized the
 * whole request answers `404` and nothing changes. A write can still fail partway,
 * though, and then the response is `409 bulk_patch_incomplete` — which means the
 * batch is **partially applied**. That is neither a success nor a no-op, so a caller
 * must re-read the affected items rather than assume either.
 *
 * @param {string[]} mediaItemIds
 * @param {Record<string, unknown>} edits
 */
export function patchMediaItemsMetadata(mediaItemIds, edits) {
  if (mediaItemIds.length === 0) throw new Error('a bulk patch needs at least one media item')
  if (mediaItemIds.length > BULK_LIMIT) {
    throw new Error(`a bulk patch accepts at most ${BULK_LIMIT} media items`)
  }
  const patch = buildMetadataPatch('mediaItem', edits)
  return request('/media-items/metadata', {
    method: 'PATCH',
    body: Object.fromEntries(mediaItemIds.map((id) => [id, patch])),
  })
}

/** The facet names the server accepts, case-sensitively. */
export const FACETS = Object.freeze([
  'genre',
  'seriesTag',
  'bookTag',
  'language',
  'publisher',
  'ageRating',
  'sharingLabel',
  'releaseYear',
])

export function readFacet(facet, { libraryId = null } = {}) {
  if (!FACETS.includes(facet)) throw new Error(`unknown facet: ${facet}`)
  return request('/facets', { query: { facet, libraryId } })
}

export function readAuthorFacet({ libraryId = null, page = 0, size = 50 } = {}) {
  return request('/facets/authors', { query: { libraryId, page, size } })
}
