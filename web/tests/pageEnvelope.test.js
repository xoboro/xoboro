import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Keeps the client's idea of which endpoints are paged in agreement with the server's.
 *
 * This exists because of a defect that no unit test could have caught. All three
 * duplicate-page routes answer the page envelope; the client assigned the response
 * straight to an array. The screen rendered neither rows nor an empty state — an
 * envelope is not iterable and its `undefined` length does not equal zero — and every
 * test passed, because every fixture was hand-written as a bare array. A mocked test
 * cannot find a wrong assumption about a shape when the mock encodes the same
 * assumption.
 *
 * The spec could not have caught it either: `XoboroPage` was defined and described as
 * "the envelope every paged catalog collection returns", and referenced by exactly zero
 * endpoints. There was nothing for either side to be checked against.
 *
 * What this test is: a tripwire that forces a decision. It does not prove a consumer
 * reads `.items` — that is not statically visible. It proves the two lists agree, so a
 * newly paged endpoint cannot arrive without somebody noticing that its consumer has to
 * change.
 */
const SPEC = join(process.cwd(), '..', 'server/app/src/main/resources/openapi/xoboro-native-v1.yaml')

/**
 * Every native endpoint that answers the page envelope.
 *
 * A path here answers `items`/`totalItems`, so a consumer must read `.items` rather than
 * iterating the response. A path absent from here returns a bare array.
 *
 * The bottom nine arrived after the server was asked instead of the description. All of
 * them answered the envelope while documented as `{ type: object }`, and this test passed
 * throughout, because a missing entry here and a missing declaration there are the same
 * omission written twice. `XoboroNativePageEnvelopeContractTest` probes the parameterless
 * ones against a real server, which is the check this file cannot perform.
 */
const PAGED = [
  '/api/xoboro/v1/series',
  '/api/xoboro/v1/media-items',
  '/api/xoboro/v1/series/{seriesId}/media-items',
  '/api/xoboro/v1/authentication-activity',
  '/api/xoboro/v1/history',
  '/api/xoboro/v1/duplicate-pages',
  '/api/xoboro/v1/duplicate-pages/decided',
  '/api/xoboro/v1/duplicate-pages/{pageHash}/media-items',
  '/api/xoboro/v1/collections/{collectionId}/series',
  '/api/xoboro/v1/read-lists/{readListId}/media-items',
  '/api/xoboro/v1/facets/authors',
  '/api/xoboro/v1/me/authentication-activity',
  '/api/xoboro/v1/media-items/feeds/keep-reading',
  '/api/xoboro/v1/media-items/feeds/new',
  '/api/xoboro/v1/media-items/feeds/on-deck',
  '/api/xoboro/v1/media-items/feeds/recently-read',
  '/api/xoboro/v1/media-items/feeds/updated',
  '/api/xoboro/v1/series/feeds/new',
  '/api/xoboro/v1/series/feeds/recently-read',
  '/api/xoboro/v1/series/feeds/updated',
]

/** Paths whose GET declares the shared envelope as its 200 body. */
function pagedInSpec(spec) {
  const found = []
  let path = null
  let inGet = false
  let in200 = false
  for (const line of spec.split('\n')) {
    const pathMatch = /^ {2}(\/\S+):\s*$/.exec(line)
    if (pathMatch) {
      path = pathMatch[1]
      inGet = false
      in200 = false
      continue
    }
    if (/^ {4}\w+:\s*$/.test(line)) inGet = line.trim() === 'get:'
    if (inGet && line.trim() === '"200":') in200 = true
    else if (/^ {8}"\d{3}":/.test(line)) in200 = false
    if (in200 && line.includes('XoboroPage"')) {
      found.push(path)
      in200 = false
    }
  }
  return found
}

describe('page envelope', () => {
  const spec = readFileSync(SPEC, 'utf8')

  it('declares the shared envelope on every endpoint the client pages', () => {
    // Read from the spec rather than asserted as a count, so the failure names the path
    // that disagrees instead of a number that moved.
    const declared = pagedInSpec(spec).sort()
    expect(declared).toEqual([...PAGED].sort())
  })

  it('has a page envelope component that something actually references', () => {
    // The state this whole file exists to prevent: a documented envelope no endpoint
    // claims to return, which is what left both sides uncheckable.
    expect(spec).toContain('XoboroPage:')
    expect(spec.match(/XoboroPage"/g)?.length ?? 0).toBeGreaterThan(0)
  })

  it('describes the envelope with the fields a consumer depends on', () => {
    // `items` and `totalItems` are the two a screen cannot work without: one to iterate,
    // one to state the total rather than showing "50" with no denominator.
    //
    // The `properties:` block alone, and each field matched as a key at its own
    // indentation. An earlier version searched the whole component for the field name,
    // which the `required: [...]` line satisfies on its own — deleting every property
    // left it passing. It also sliced the component with an `indexOf` that returned `-1`,
    // so "the component" was the remainder of the file.
    //
    // Presence alone also does not say the field is usable: `totalItems` declared as a
    // string still fails a consumer that adds it to a page count. Each field's declared
    // type is asserted too, not just that the key exists.
    const properties = envelopeProperties(spec)
    const lines = properties.split('\n')
    const fieldTypes = {
      items: 'array',
      page: 'integer',
      size: 'integer',
      totalItems: 'integer',
      totalPages: 'integer',
      hasPrevious: 'boolean',
      hasNext: 'boolean',
    }
    for (const [field, type] of Object.entries(fieldTypes)) {
      const index = lines.findIndex((line) => new RegExp(`^ {8}${field}:`).test(line))
      expect(index, `envelope is missing the ${field} property`).toBeGreaterThan(-1)
      // Most fields declare their type inline (`field: { type: x }`); `items` instead
      // opens a block and states its own type (as opposed to its elements') on the next
      // line, so that line is checked when the key's own line has none.
      const declaration = lines[index].includes('type:') ? lines[index] : lines[index + 1]
      expect(declaration, `${field} is not declared as type: ${type}`).toMatch(
        new RegExp(`type: ${type}\\b`),
      )
    }
  })
})

/** The `properties:` block of the `XoboroPage` component, and nothing else. */
function envelopeProperties(spec) {
  const lines = spec.split('\n')
  const start = lines.indexOf('    XoboroPage:')
  expect(start, 'the spec has no XoboroPage component').toBeGreaterThan(-1)
  const propertiesAt = lines.indexOf('      properties:', start)
  expect(propertiesAt, 'XoboroPage declares no properties').toBeGreaterThan(start)
  const body = []
  for (const line of lines.slice(propertiesAt + 1)) {
    // Ends at the first line that is not inside the block: a shallower key, or the next
    // component. A blank line is kept, so a gap between properties does not truncate it.
    if (line.trim() !== '' && !line.startsWith('        ')) break
    body.push(line)
  }
  expect(body.length, 'the properties block came out empty').toBeGreaterThan(0)
  return body.join('\n')
}
