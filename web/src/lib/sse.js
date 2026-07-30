import { writable } from 'svelte/store'
import { API_BASE } from './http.js'

/**
 * Every domain event the server publishes today.
 *
 * The client subscribes by name, which is also how it satisfies the contract's
 * rule that **unknown event names must be ignored**: a name this list does not
 * mention is never listened for, so the server can add one in a later release
 * without that being a breaking change.
 *
 * Payloads carry identifiers only, never an entity snapshot. An event is an
 * invalidation signal — the handler re-queries the affected view rather than
 * replaying `ids` one fetch at a time.
 */
export const DOMAIN_EVENTS = Object.freeze([
  'library.added', 'library.changed', 'library.removed',
  'media-item.added', 'media-item.changed', 'media-item.removed',
  'series.added', 'series.changed', 'series.removed',
  'collection.added', 'collection.changed', 'collection.removed',
  'read-list.added', 'read-list.changed', 'read-list.removed',
  'read-progress.changed', 'read-progress.removed',
  'series-progress.changed', 'series-progress.removed',
  'poster.changed',
])

export const StreamStatus = Object.freeze({
  IDLE: 'IDLE',
  CONNECTING: 'CONNECTING',
  LIVE: 'LIVE',
  /** Waiting out our own backoff after the server refused the connection. */
  RETRYING: 'RETRYING',
  /**
   * Closed and deliberately not reconnecting. Reached when this connection was
   * superseded by another of the same user's streams — reconnecting would evict
   * one of their other tabs, and then that tab would evict this one.
   */
  SUPERSEDED: 'SUPERSEDED',
})

const BACKOFF_MILLIS = Object.freeze([1_000, 2_000, 4_000, 8_000, 16_000, 30_000])

/**
 * Creates the application's single event-stream subscription.
 *
 * One connection per session, fanned out to subscribers. Not one per screen: the
 * server allows each user only four concurrent streams and evicts the oldest
 * beyond that, so a console with eight live panels opening eight streams would
 * spend its time evicting itself.
 *
 * Reconnection is left to the browser wherever possible. A native `EventSource`
 * already retries a dropped connection and already sends `Last-Event-ID`, which
 * is exactly the resume protocol the server documents; reimplementing it would
 * mean reimplementing it worse. Our own backoff only covers the case the browser
 * treats as fatal — a non-2xx response, such as `503 event_stream_capacity`.
 *
 * @param {object} [options]
 * @param {(url: string) => EventSource} [options.eventSourceFactory]
 * @param {(fn: () => void, delay: number) => unknown} [options.schedule]
 * @param {(handle: unknown) => void} [options.cancel]
 */
export function createEventHub(options = {}) {
  const {
    eventSourceFactory = (url) => new EventSource(url),
    schedule = (fn, delay) => setTimeout(fn, delay),
    cancel = (handle) => clearTimeout(handle),
  } = options

  const status = writable(StreamStatus.IDLE)
  /** @type {Map<string, Set<Function>>} */
  const domainHandlers = new Map()
  const resyncHandlers = new Set()
  const readyHandlers = new Set()

  let source = null
  let retryHandle = null
  let attempt = 0
  let stopped = true

  function emitDomain(name, event) {
    const handlers = domainHandlers.get(name)
    if (!handlers?.size) return
    let payload
    try {
      payload = JSON.parse(event.data)
    } catch {
      // A frame whose data is not JSON is not something a screen can act on. It
      // is dropped rather than passed along as a half-parsed object, and it is
      // deliberately not treated as loss: only a resync frame means that.
      return
    }
    const message = Object.freeze({ name, ...payload })
    for (const handler of handlers) handler(message)
  }

  function closeSource() {
    source?.close()
    source = null
  }

  function scheduleRetry() {
    const delay = BACKOFF_MILLIS[Math.min(attempt, BACKOFF_MILLIS.length - 1)]
    attempt += 1
    status.set(StreamStatus.RETRYING)
    retryHandle = schedule(() => {
      retryHandle = null
      if (!stopped) connect()
    }, delay)
  }

  function handleControl(event) {
    let payload = {}
    try {
      payload = JSON.parse(event.data)
    } catch {
      payload = {}
    }
    return payload
  }

  function connect() {
    closeSource()
    status.set(StreamStatus.CONNECTING)
    source = eventSourceFactory(`${API_BASE}/events`)

    source.addEventListener('stream.ready', (event) => {
      const payload = handleControl(event)
      // A successful open resets the backoff. Without this, a stream that opens,
      // survives its one-hour lifetime and reopens would inherit a maximal delay
      // from a refusal that happened hours earlier.
      attempt = 0
      status.set(StreamStatus.LIVE)
      for (const handler of readyHandlers) handler(payload)
    })

    source.addEventListener('stream.resync-required', (event) => {
      const payload = handleControl(event)
      const reason = payload.reason ?? 'gap'
      for (const handler of resyncHandlers) handler(Object.freeze({ ...payload, reason }))

      if (reason === 'superseded') {
        // This connection lost a race against another of the same user's tabs.
        // Reconnecting would evict that tab, which would reconnect and evict
        // this one. Stop, and let the shell decide whether it still wants a
        // stream at all.
        stopped = true
        closeSource()
        status.set(StreamStatus.SUPERSEDED)
        return
      }
      if (reason === 'revoked') {
        // Authorization changed, and the server closes the connection right
        // after this frame. The shell must re-read the session before deciding
        // whether to reconnect: the subscriber may no longer be allowed one.
        closeSource()
        status.set(StreamStatus.IDLE)
      }
      // `gap` and `overflow` both leave the stream usable. Subscribers have been
      // told to re-query; nothing else to do here.
    })

    for (const name of DOMAIN_EVENTS) {
      source.addEventListener(name, (event) => emitDomain(name, event))
    }

    source.addEventListener('error', () => {
      // CONNECTING means the browser is already retrying with `Last-Event-ID`,
      // which is the documented resume path — leave it alone. CLOSED means the
      // browser gave up, which is what it does for a non-2xx reply such as
      // `503 event_stream_capacity`, so the backoff is ours to run.
      if (source?.readyState === 0 /* CONNECTING */) {
        status.set(StreamStatus.CONNECTING)
        return
      }
      closeSource()
      if (!stopped) scheduleRetry()
    })
  }

  return {
    status,

    start() {
      if (source || retryHandle) return
      stopped = false
      attempt = 0
      connect()
    },

    stop() {
      stopped = true
      // Compared against null rather than tested for truthiness: a scheduler
      // handle is allowed to be 0, and `if (retryHandle)` would then leave the
      // first pending retry to fire after the hub was told to stop.
      if (retryHandle !== null) cancel(retryHandle)
      retryHandle = null
      closeSource()
      status.set(StreamStatus.IDLE)
    },

    /**
     * Subscribes to one or more domain events.
     *
     * @param {string|string[]} names
     * @param {(message: object) => void} handler
     * @returns {() => void} unsubscribe
     */
    on(names, handler) {
      const list = Array.isArray(names) ? names : [names]
      for (const name of list) {
        if (!DOMAIN_EVENTS.includes(name)) {
          throw new Error(`unknown event name: ${name}`)
        }
        if (!domainHandlers.has(name)) domainHandlers.set(name, new Set())
        domainHandlers.get(name).add(handler)
      }
      return () => {
        for (const name of list) domainHandlers.get(name)?.delete(handler)
      }
    },

    /**
     * Subscribes to the only trustworthy staleness signal the server sends.
     *
     * Gaps in `seq` are **not** loss: `seq` counts events published before
     * per-subscriber filtering, so a subscriber whose grants exclude some
     * libraries legitimately sees it jump. Only a resync frame means a view may
     * be stale.
     *
     * @param {(message: {reason: string, seq?: number}) => void} handler
     */
    onResync(handler) {
      resyncHandlers.add(handler)
      return () => resyncHandlers.delete(handler)
    },

    /** @param {(message: {seq?: number, resumed: boolean}) => void} handler */
    onReady(handler) {
      readyHandlers.add(handler)
      return () => readyHandlers.delete(handler)
    },
  }
}
