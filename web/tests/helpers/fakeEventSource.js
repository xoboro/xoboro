/**
 * A minimal stand-in for `EventSource`.
 *
 * jsdom has no `EventSource`, and even where one exists the behaviour that
 * matters here — what `readyState` is when `error` fires — is exactly what a
 * test needs to control, because that is how the hub decides whether the browser
 * is already retrying or has given up.
 */
export class FakeEventSource {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2

  /** Every instance created, in order, so a test can drive reconnects. */
  static instances = []

  static reset() {
    FakeEventSource.instances = []
  }

  constructor(url) {
    this.url = url
    this.readyState = FakeEventSource.CONNECTING
    this.listeners = new Map()
    this.closed = false
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, handler) {
    if (!this.listeners.has(name)) this.listeners.set(name, new Set())
    this.listeners.get(name).add(handler)
  }

  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }

  /** Delivers a server frame. `data` is serialised exactly as SSE would carry it. */
  emit(name, data) {
    this.readyState = FakeEventSource.OPEN
    const event = { type: name, data: typeof data === 'string' ? data : JSON.stringify(data) }
    for (const handler of this.listeners.get(name) ?? []) handler(event)
  }

  /** Delivers a raw frame whose data is not JSON. */
  emitRaw(name, data) {
    for (const handler of this.listeners.get(name) ?? []) handler({ type: name, data })
  }

  /**
   * Fires `error`.
   *
   * @param {number} readyState `CONNECTING` when the browser is retrying by
   *   itself, `CLOSED` when it has given up — which is what it does for a
   *   non-2xx reply such as `503 event_stream_capacity`.
   */
  fail(readyState) {
    this.readyState = readyState
    for (const handler of this.listeners.get('error') ?? []) handler({ type: 'error' })
  }
}

/** A `schedule`/`cancel` pair a test can run by hand instead of waiting. */
export function manualScheduler() {
  const pending = []
  return {
    schedule(fn, delay) {
      pending.push({ fn, delay })
      return pending.length - 1
    },
    cancel(handle) {
      if (pending[handle]) pending[handle].cancelled = true
    },
    get delays() {
      return pending.filter((task) => !task.cancelled).map((task) => task.delay)
    },
    runNext() {
      const task = pending.find((candidate) => !candidate.ran && !candidate.cancelled)
      if (!task) throw new Error('nothing scheduled')
      task.ran = true
      task.fn()
    },
  }
}
