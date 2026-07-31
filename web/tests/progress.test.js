import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ProgressConflict,
  progressStamp,
  resetProgressClock,
  resumePage,
  writeProgress,
} from '../src/lib/api/progress.js'
import { deviceIdentity } from '../src/lib/device.js'

function reply(body, status = 200) {
  const text = body === null ? '' : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => text,
    json: async () => JSON.parse(text),
  }
}

beforeEach(() => {
  resetProgressClock()
  globalThis.localStorage?.clear?.()
})

describe('progressStamp', () => {
  it('never repeats a value, because equal counts as stale', () => {
    // The server refuses a modifiedAtMillis older than *or equal to* the stored one, so
    // two writes inside the same millisecond would see the second rejected with a
    // conflict the reader did not cause.
    const frozen = () => 1_000
    expect(progressStamp(frozen)).toBe(1_000)
    expect(progressStamp(frozen)).toBe(1_001)
    expect(progressStamp(frozen)).toBe(1_002)
  })

  it('keeps increasing when the wall clock goes backwards', () => {
    // Clocks move backwards across an NTP correction or a daylight-saving change. A
    // bare Date.now() would then produce a stamp the server has already stored, and
    // every write would conflict until real time caught up.
    let wall = 5_000
    expect(progressStamp(() => wall)).toBe(5_000)
    wall = 4_000
    expect(progressStamp(() => wall)).toBe(5_001)
  })

  it('follows the wall clock forward', () => {
    expect(progressStamp(() => 10)).toBe(10)
    expect(progressStamp(() => 500)).toBe(500)
  })
})

describe('writeProgress', () => {
  it('sends the position with a device identity and a client clock', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply(null, 204))
    globalThis.fetch = fetchImpl

    const conflict = await writeProgress('m1', { page: 4 })
    expect(conflict).toBeNull()

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toContain('/media-items/m1/progress')
    expect(init.method).toBe('PUT')
    const sent = JSON.parse(init.body)
    expect(sent.page).toBe(4)
    expect(sent.deviceId).toBeTruthy()
    expect(sent.deviceName).toBeTruthy()
    expect(typeof sent.modifiedAtMillis).toBe('number')
  })

  it('omits the locator when there is none rather than sending null', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply(null, 204))
    globalThis.fetch = fetchImpl

    await writeProgress('m1', { page: 1 })
    expect('locator' in JSON.parse(fetchImpl.mock.calls[0][1].body)).toBe(false)
  })

  it('returns the newer position on a conflict instead of swallowing it', async () => {
    // The base this UI grew from wrote progress with `.catch(() => {})`, which threw
    // away the whole point of the refusal: it exists so a second device cannot silently
    // rewind the reader's place.
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(reply({ code: 'stale_progress', message: 'stale' }, 409))
      .mockResolvedValueOnce(reply({ id: 'm1', readProgress: { page: 42, completed: false } }))
    globalThis.fetch = fetchImpl

    const conflict = await writeProgress('m1', { page: 7 })
    expect(conflict).toBeInstanceOf(ProgressConflict)
    // Read back from the server rather than guessed: the refusal says only that
    // something newer exists, not what it is.
    expect(conflict.current).toEqual({ page: 42, completed: false })
  })

  it('propagates a failure that is not a conflict', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(reply({ code: 'media_item_not_found' }, 404))
    await expect(writeProgress('m1', { page: 1 })).rejects.toMatchObject({
      code: 'media_item_not_found',
    })
  })
})

describe('resumePage', () => {
  it('starts at the first page for an unread item', () => {
    // One-based on this surface, with no zero_based parameter to confuse it with.
    expect(resumePage(null, 10)).toBe(1)
    expect(resumePage({ page: 0, completed: false }, 10)).toBe(1)
  })

  it('starts again from the beginning for a finished item', () => {
    // Resuming at the last page of something already read would offer to re-read the
    // final page forever.
    expect(resumePage({ page: 10, completed: true }, 10)).toBe(1)
  })

  it('resumes where the reader stopped', () => {
    expect(resumePage({ page: 4, completed: false }, 10)).toBe(4)
  })

  it('clamps a stored page past the end', () => {
    // A page count can shrink when a file is replaced, and scrolling to page 99 of a
    // 10-page item would show a blank reader.
    expect(resumePage({ page: 99, completed: false }, 10)).toBe(10)
  })
})

describe('deviceIdentity', () => {
  it('is stable across calls', () => {
    // A fresh identity per reload would make one browser read as a different device on
    // every visit, and the progress history becomes unusable.
    const first = deviceIdentity()
    expect(deviceIdentity().deviceId).toBe(first.deviceId)
  })

  it('discards a malformed stored value rather than trusting it', () => {
    // Plain localStorage is user-writable. Nothing is protected by this value, but a
    // broken one must not propagate into a request body.
    localStorage.setItem('xoboro.device', '{"deviceId":123}')
    expect(typeof deviceIdentity().deviceId).toBe('string')
    expect(deviceIdentity().deviceId).not.toBe('123')
  })

  it('still produces an identity when storage refuses', () => {
    const hostile = {
      getItem: () => {
        throw new Error('denied')
      },
      setItem: () => {
        throw new Error('denied')
      },
    }
    expect(deviceIdentity(hostile).deviceId).toBeTruthy()
  })
})
