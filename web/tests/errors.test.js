import { describe, expect, it } from 'vitest'
import {
  FIELD_FOR_CODE,
  Treatment,
  XoboroApiError,
  XoboroNetworkError,
  parseRetryAfter,
} from '../src/lib/errors.js'

const error = (code, extra = {}) => new XoboroApiError({ status: 400, code, ...extra })

describe('treatment', () => {
  it('maps each code that needs its own reaction', () => {
    expect(error('authentication_required').treatment).toBe(Treatment.REAUTHENTICATE)
    expect(error('rate_limit_exceeded').treatment).toBe(Treatment.THROTTLED)
    expect(error('stale_progress').treatment).toBe(Treatment.PROGRESS_CONFLICT)
    expect(error('library_unavailable').treatment).toBe(Treatment.LIBRARY_UNAVAILABLE)
    expect(error('server_already_claimed').treatment).toBe(Treatment.SETUP_DONE)
    expect(error('event_stream_capacity').treatment).toBe(Treatment.STREAM_AT_CAPACITY)
    expect(error('invalid_request').treatment).toBe(Treatment.FORM)
  })

  it('recognises authorization and lookup failures by suffix', () => {
    // The suffix is the contract rather than an enumeration: the native surface
    // has one *_forbidden and one *_not_found code per route group, and new route
    // groups keep arriving. An enumeration would go stale silently.
    expect(error('library_administration_forbidden').treatment).toBe(Treatment.FORBIDDEN)
    expect(error('task_administration_forbidden').treatment).toBe(Treatment.FORBIDDEN)
    expect(error('operational_metrics_forbidden').treatment).toBe(Treatment.FORBIDDEN)
    expect(error('forbidden').treatment).toBe(Treatment.FORBIDDEN)

    expect(error('media_item_not_found').treatment).toBe(Treatment.NOT_FOUND)
    expect(error('backup_not_found').treatment).toBe(Treatment.NOT_FOUND)
    expect(error('not_found').treatment).toBe(Treatment.NOT_FOUND)
  })

  it('treats an unrecognised code as generic rather than failing', () => {
    // The server may add a code without that being a breaking change, so an
    // unknown one must degrade to a generic message instead of throwing.
    expect(error('some_code_from_a_later_release').treatment).toBe(Treatment.UNKNOWN)
  })

  it('treats a rejected query as a client defect, not a field error', () => {
    // invalid_query means the client built a bad request. There is no field for
    // the user to fix, so offering one would be misleading.
    expect(error('invalid_query').treatment).toBe(Treatment.UNKNOWN)
    expect(error('invalid_query').field).toBeNull()
  })
})

describe('field scoping', () => {
  it('points each validation code at the input that owns it', () => {
    expect(error('library_root_missing').field).toBe('source.location')
    expect(error('library_root_not_directory').field).toBe('source.location')
    expect(error('library_root_overlap').field).toBe('source.location')
    expect(error('library_name_conflict').field).toBe('name')
    expect(error('invalid_credentials').field).toBe('password')
  })

  it('gives every field-scoped code the FIELD treatment', () => {
    for (const code of Object.keys(FIELD_FOR_CODE)) {
      expect(error(code).treatment).toBe(Treatment.FIELD)
    }
  })
})

describe('rendering', () => {
  it('never puts the server message where a user would see it', () => {
    const caught = error('library_unavailable', {
      serverMessage: 'Library storage is unavailable',
    })
    // messageKey is what the UI renders; the server's English prose is kept only
    // for logs, under a name that cannot be mistaken for display text.
    expect(caught.messageKey).toBe('errors.library_unavailable')
    expect(caught.messageKey).not.toContain('Library storage')
    expect(caught.serverMessage).toBe('Library storage is unavailable')
  })

  it('gives a network failure its own code and treatment', () => {
    const caught = new XoboroNetworkError(new TypeError('failed to fetch'))
    expect(caught.treatment).toBe(Treatment.OFFLINE)
    expect(caught.messageKey).toBe('errors.network_unreachable')
  })
})

describe('parseRetryAfter', () => {
  const now = Date.parse('2026-07-31T00:00:00Z')

  it('reads a delay in seconds', () => {
    expect(parseRetryAfter('42', now)).toBe(42)
    expect(parseRetryAfter('  42  ', now)).toBe(42)
  })

  it('reads an HTTP date as a delay from now', () => {
    expect(parseRetryAfter('Fri, 31 Jul 2026 00:00:30 GMT', now)).toBe(30)
  })

  it('clamps a date already in the past to zero', () => {
    // A negative wait would disable a submit button forever in one direction and
    // fire an immediate retry storm in the other.
    expect(parseRetryAfter('Fri, 31 Jul 2026 00:00:00 GMT', now + 90_000)).toBe(0)
  })

  it('returns null when there is nothing usable', () => {
    expect(parseRetryAfter(null, now)).toBeNull()
    expect(parseRetryAfter('', now)).toBeNull()
    expect(parseRetryAfter('soon', now)).toBeNull()
  })
})
