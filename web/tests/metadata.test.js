import { describe, expect, it, vi } from 'vitest'
import {
  BOOK_PATCH_FIELDS,
  BULK_LIMIT,
  CLEAR,
  FACETS,
  SERIES_PATCH_FIELDS,
  buildMetadataPatch,
  patchMediaItemsMetadata,
  readFacet,
} from '../src/lib/api/metadata.js'

/**
 * The patch endpoints have two opposite null semantics and nothing in the JSON tells
 * them apart. These tests are the encoding of that contract.
 */
describe('buildMetadataPatch', () => {
  it('omits a field that was not edited', () => {
    // Absence is what preserves a stored value, for both kinds of field.
    expect(buildMetadataPatch('series', {})).toEqual({})
  })

  it('clears a patch field with an explicit null', () => {
    expect(buildMetadataPatch('series', { genres: CLEAR })).toEqual({ genres: null })
    expect(buildMetadataPatch('series', { ageRating: CLEAR })).toEqual({ ageRating: null })
    expect(buildMetadataPatch('mediaItem', { isbn: CLEAR })).toEqual({ isbn: null })
  })

  it('clears a plain optional string with an empty string, never null', () => {
    // `null` on one of these is silently ignored, so the request would answer 200
    // having changed nothing — a save that looks like it worked.
    expect(buildMetadataPatch('series', { title: CLEAR })).toEqual({ title: '' })
    expect(buildMetadataPatch('series', { publisher: CLEAR })).toEqual({ publisher: '' })
    expect(buildMetadataPatch('mediaItem', { number: CLEAR })).toEqual({ number: '' })
  })

  it('handles summary opposite ways for a series and a media item', () => {
    // The same field name with the opposite meaning for null, depending on which
    // resource is being edited. This is the trap the module exists for.
    expect(SERIES_PATCH_FIELDS).not.toContain('summary')
    expect(BOOK_PATCH_FIELDS).toContain('summary')

    expect(buildMetadataPatch('series', { summary: CLEAR })).toEqual({ summary: '' })
    expect(buildMetadataPatch('mediaItem', { summary: CLEAR })).toEqual({ summary: null })
  })

  it('refuses to clear a field that has no empty value', () => {
    // An enum has neither a clearing null nor an empty string. Sending null anyway
    // would be discarded by the server while it answered 200.
    expect(() => buildMetadataPatch('series', { status: CLEAR })).toThrow(/cannot be cleared/)
    expect(() => buildMetadataPatch('mediaItem', { numberSort: CLEAR })).toThrow(
      /cannot be cleared/,
    )
  })

  it('passes an ordinary value through untouched', () => {
    expect(buildMetadataPatch('series', { title: 'Synthetic', genres: ['a', 'b'] })).toEqual({
      title: 'Synthetic',
      genres: ['a', 'b'],
    })
  })

  it('rejects an unknown resource kind', () => {
    expect(() => buildMetadataPatch('book', { title: 'x' })).toThrow(/unknown metadata kind/)
  })
})

describe('bulk patch', () => {
  it('refuses an empty batch and one over the limit', () => {
    // The server rejects more than 200 rather than truncating, so failing here is the
    // same answer sooner.
    expect(() => patchMediaItemsMetadata([], { title: 'x' })).toThrow(/at least one/)
    const tooMany = Array.from({ length: BULK_LIMIT + 1 }, (_value, index) => `m${index}`)
    expect(() => patchMediaItemsMetadata(tooMany, { title: 'x' })).toThrow(/at most 200/)
  })

  it('sends the same patch keyed by identifier', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => '[]',
      json: async () => [],
    })
    globalThis.fetch = fetchImpl

    await patchMediaItemsMetadata(['m1', 'm2'], { tags: CLEAR })
    const sent = JSON.parse(fetchImpl.mock.calls[0][1].body)
    expect(sent).toEqual({ m1: { tags: null }, m2: { tags: null } })
  })
})

describe('facets', () => {
  it('accepts only the documented facet names', () => {
    // Case-sensitive on the server, and an unknown value answers 400 invalid_query —
    // which is a client defect, so it is caught here instead.
    for (const facet of FACETS) {
      expect(() => readFacet(facet)).not.toThrow()
    }
    expect(() => readFacet('genres')).toThrow(/unknown facet/)
    expect(() => readFacet('Genre')).toThrow(/unknown facet/)
  })
})
