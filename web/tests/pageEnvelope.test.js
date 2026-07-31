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
 * Every native endpoint that answers the page envelope, as the client understands it.
 *
 * A path here has a consumer that must read `.items` and must send `page`. A path
 * absent from here returns a bare array, and its consumer iterates the response
 * directly.
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
    const component = spec.slice(spec.indexOf('    XoboroPage:'))
    const body = component.slice(0, component.indexOf('\n    Xoboro', 1))
    for (const field of ['items', 'page', 'size', 'totalItems', 'totalPages', 'hasPrevious', 'hasNext']) {
      expect(body, `envelope is missing ${field}`).toContain(field)
    }
  })
})
