import { get } from 'svelte/store'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DOMAIN_EVENTS, StreamStatus, createEventHub } from '../src/lib/sse.js'
import { FakeEventSource, manualScheduler } from './helpers/fakeEventSource.js'

function hubWith() {
  const scheduler = manualScheduler()
  const hub = createEventHub({
    eventSourceFactory: (url) => new FakeEventSource(url),
    schedule: scheduler.schedule,
    cancel: scheduler.cancel,
  })
  return { hub, scheduler, latest: () => FakeEventSource.instances.at(-1) }
}

beforeEach(() => FakeEventSource.reset())

describe('subscription', () => {
  it('rejects an event name the server does not publish', () => {
    // A typo would otherwise register a listener that can never fire, and the
    // screen would look like it worked while silently never updating.
    const { hub } = hubWith()
    hub.start()
    expect(() => hub.on('media-item.updated', () => {})).toThrow(/unknown event name/)
  })

  it('ignores a frame whose name it does not know', () => {
    // The contract requires unknown names to be ignored so the server can add one
    // without that being a breaking change. Listening only for known names is how
    // that is satisfied — this pins that an unknown frame is inert, not fatal.
    const { hub, latest } = hubWith()
    hub.start()
    expect(() => latest().emit('media-item.rescanned', { ids: ['m1'] })).not.toThrow()
    expect(get(hub.status)).not.toBe(StreamStatus.SUPERSEDED)
  })

  it('delivers the payload with the event name attached', () => {
    const { hub, latest } = hubWith()
    const handler = vi.fn()
    hub.start()
    hub.on('media-item.changed', handler)
    latest().emit('media-item.changed', { ids: ['m1'], libraryId: 'l1', seriesId: 's1' })

    expect(handler).toHaveBeenCalledWith({
      name: 'media-item.changed',
      ids: ['m1'],
      libraryId: 'l1',
      seriesId: 's1',
    })
  })

  it('drops a frame whose data is not JSON without treating it as loss', () => {
    const { hub, latest } = hubWith()
    const handler = vi.fn()
    const resync = vi.fn()
    hub.start()
    hub.on('series.changed', handler)
    hub.onResync(resync)

    expect(() => latest().emitRaw('series.changed', 'not json')).not.toThrow()
    expect(handler).not.toHaveBeenCalled()
    // Only a resync frame means a view may be stale. A malformed frame is not
    // evidence of loss, so inventing one here would make clients re-query for
    // nothing.
    expect(resync).not.toHaveBeenCalled()
  })

  it('stops delivering after unsubscribe', () => {
    const { hub, latest } = hubWith()
    const handler = vi.fn()
    hub.start()
    const off = hub.on(['series.added', 'series.removed'], handler)
    off()
    latest().emit('series.added', { ids: ['s1'] })
    expect(handler).not.toHaveBeenCalled()
  })

  it('covers every event name the server documents', () => {
    // A name missing from this list is a name no screen can ever subscribe to,
    // and `on` would throw for it — so the list is part of the contract.
    expect(DOMAIN_EVENTS).toContain('poster.changed')
    expect(DOMAIN_EVENTS).toContain('read-progress.changed')
    expect(DOMAIN_EVENTS).toContain('series-progress.removed')
    expect(new Set(DOMAIN_EVENTS).size).toBe(DOMAIN_EVENTS.length)
  })
})

describe('seq is not a loss signal', () => {
  it('never reports a resync from gaps between frames', () => {
    // seq counts events published before per-subscriber filtering, so a
    // subscriber whose grants exclude some libraries legitimately sees it jump
    // from 1837 to 1904. Inferring loss from that would make every restricted
    // account re-query constantly.
    const { hub, latest } = hubWith()
    const resync = vi.fn()
    hub.start()
    hub.onResync(resync)

    latest().emit('stream.ready', { resumed: false })
    latest().emit('series.changed', { ids: ['s1'], libraryId: 'l1' })
    latest().emit('series.changed', { ids: ['s9'], libraryId: 'l1' })

    expect(resync).not.toHaveBeenCalled()
    expect(get(hub.status)).toBe(StreamStatus.LIVE)
  })
})

describe('resync frames', () => {
  it('keeps the stream open for gap and overflow', () => {
    // Both leave the connection usable: subscribers re-query, and tearing the
    // stream down would cost a reconnect for no reason.
    for (const reason of ['gap', 'overflow']) {
      FakeEventSource.reset()
      const { hub, latest, scheduler } = hubWith()
      const resync = vi.fn()
      hub.start()
      hub.onResync(resync)
      latest().emit('stream.resync-required', { reason, seq: 42 })

      expect(resync).toHaveBeenCalledWith(expect.objectContaining({ reason }))
      expect(latest().closed).toBe(false)
      expect(scheduler.delays).toEqual([])
    }
  })

  it('stops for good when superseded, without reconnecting', () => {
    // superseded means this connection lost a race against another of the same
    // user's tabs. Reconnecting would evict that tab, which would reconnect and
    // evict this one - a loop that burns the user's four-stream budget forever.
    const { hub, latest, scheduler } = hubWith()
    hub.start()
    latest().emit('stream.resync-required', { reason: 'superseded' })

    expect(latest().closed).toBe(true)
    expect(get(hub.status)).toBe(StreamStatus.SUPERSEDED)
    expect(scheduler.delays).toEqual([])
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('closes and waits for the shell when authorization is revoked', () => {
    // The server closes the connection right after this frame. Whether a new one
    // is allowed depends on the re-read session, so the hub must not guess.
    const { hub, latest } = hubWith()
    const resync = vi.fn()
    hub.start()
    hub.onResync(resync)
    latest().emit('stream.resync-required', { reason: 'revoked' })

    expect(resync).toHaveBeenCalledWith(expect.objectContaining({ reason: 'revoked' }))
    expect(latest().closed).toBe(true)
    expect(get(hub.status)).toBe(StreamStatus.IDLE)
  })

  it('defaults a reason-less resync frame to gap rather than dropping it', () => {
    const { hub, latest } = hubWith()
    const resync = vi.fn()
    hub.start()
    hub.onResync(resync)
    latest().emitRaw('stream.resync-required', 'not json')
    expect(resync).toHaveBeenCalledWith(expect.objectContaining({ reason: 'gap' }))
  })
})

describe('reconnection', () => {
  it('leaves a retry to the browser while it is still connecting', () => {
    // A native EventSource already retries with Last-Event-ID, which is exactly
    // the documented resume path. Running our own backoff on top would open a
    // second connection against a four-stream budget.
    const { hub, latest, scheduler } = hubWith()
    hub.start()
    latest().fail(FakeEventSource.CONNECTING)

    expect(scheduler.delays).toEqual([])
    expect(get(hub.status)).toBe(StreamStatus.CONNECTING)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('runs its own backoff once the browser has given up', () => {
    // A non-2xx reply - 503 event_stream_capacity is the documented one - is fatal
    // to an EventSource, so nothing reconnects unless we do.
    const { hub, latest, scheduler } = hubWith()
    hub.start()

    latest().fail(FakeEventSource.CLOSED)
    expect(scheduler.delays).toEqual([1_000])
    expect(get(hub.status)).toBe(StreamStatus.RETRYING)

    scheduler.runNext()
    expect(FakeEventSource.instances).toHaveLength(2)
    latest().fail(FakeEventSource.CLOSED)
    scheduler.runNext()
    latest().fail(FakeEventSource.CLOSED)

    expect(scheduler.delays).toEqual([1_000, 2_000, 4_000])
  })

  it('resets the backoff once a stream opens', () => {
    // A stream that opens, survives its one-hour lifetime and reopens must not
    // inherit a maximal delay from a refusal that happened hours earlier.
    const { hub, latest, scheduler } = hubWith()
    hub.start()

    latest().fail(FakeEventSource.CLOSED)
    scheduler.runNext()
    latest().fail(FakeEventSource.CLOSED)
    expect(scheduler.delays).toEqual([1_000, 2_000])

    scheduler.runNext()
    latest().emit('stream.ready', { resumed: true, seq: 9 })
    latest().fail(FakeEventSource.CLOSED)

    expect(scheduler.delays).toEqual([1_000, 2_000, 1_000])
  })

  it('cancels a pending retry when stopped', () => {
    const { hub, latest, scheduler } = hubWith()
    hub.start()
    latest().fail(FakeEventSource.CLOSED)
    hub.stop()

    expect(scheduler.delays).toEqual([])
    expect(get(hub.status)).toBe(StreamStatus.IDLE)
  })

  it('opens at most one connection for repeated starts', () => {
    const { hub } = hubWith()
    hub.start()
    hub.start()
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('reports ready with the resumed flag the server sent', () => {
    const { hub, latest } = hubWith()
    const ready = vi.fn()
    hub.start()
    hub.onReady(ready)
    latest().emit('stream.ready', { resumed: true, seq: 812 })

    expect(ready).toHaveBeenCalledWith({ resumed: true, seq: 812 })
    expect(get(hub.status)).toBe(StreamStatus.LIVE)
  })
})
