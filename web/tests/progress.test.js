import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  percentRead,
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

  it('sends explicit completion and keepalive for a final flush', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply(null, 204))
    globalThis.fetch = fetchImpl

    await writeProgress('m1', { page: 10, completed: false, keepalive: true })

    const sent = JSON.parse(fetchImpl.mock.calls[0][1].body)
    expect(sent.completed).toBe(false)
    expect(fetchImpl.mock.calls[0][1].keepalive).toBe(true)
  })

  it('uses stale response details for the next timestamp without a follow-up read', async () => {
    // A stale response can be an out-of-order request from this same reader. It must
    // not interrupt reading with a modal, and the stored timestamp still has to seed
    // the next write so a clock correction does not make every later update stale.
    vi.spyOn(Date, 'now').mockReturnValue(100)
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(
        reply({
          code: 'stale_progress',
          message: 'stale',
          progress: {
            page: 42,
            completed: false,
            readAtMillis: 500,
            updatedAtMillis: 500,
          },
        }, 409),
      )
      .mockResolvedValueOnce(reply(null, 204))
    globalThis.fetch = fetchImpl

    expect(await writeProgress('m1', { page: 7 })).toBeNull()
    expect(await writeProgress('m1', { page: 8 })).toBeNull()

    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(fetchImpl.mock.calls.some(([url]) => /\/media-items\/m1$/.test(url))).toBe(false)
    const nextWrite = JSON.parse(fetchImpl.mock.calls[1][1].body)
    expect(nextWrite.modifiedAtMillis).toBe(501)
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

/**
 * How far into a chapter a reader is, as a percentage.
 *
 * Shown as a bar on the series list and on a shelf card. A words-only label says
 * "page 5" and leaves a reader to work out whether that is the start or nearly the end;
 * the bar is the part that answers that at a glance.
 */
describe('percentRead', () => {
  it('is zero for a chapter never opened', () => {
    expect(percentRead(null, 10)).toBe(0)
    expect(percentRead(undefined, 10)).toBe(0)
  })

  it('is the share of the pages read', () => {
    expect(percentRead({ page: 5, completed: false }, 10)).toBe(50)
    expect(percentRead({ page: 1, completed: false }, 4)).toBe(25)
  })

  it('is a full bar once the chapter is finished, whatever page it stopped on', () => {
    // A finished chapter records the page it ended on, which is not always the last one -
    // a reader can mark it read from the list without opening it.
    expect(percentRead({ page: 3, completed: true }, 10)).toBe(100)
  })

  it('is zero rather than Infinity when the page count is unknown', () => {
    // An item whose analysis has not run yet has no page count. Dividing by it would put
    // `Infinity%` into a style attribute, which silently renders as a full bar.
    expect(percentRead({ page: 5, completed: false }, 0)).toBe(0)
    expect(percentRead({ page: 5, completed: false }, undefined)).toBe(0)
  })

  it('never exceeds a full bar', () => {
    // Progress can outlive a re-analysis that found fewer pages.
    expect(percentRead({ page: 99, completed: false }, 10)).toBe(100)
  })
})
