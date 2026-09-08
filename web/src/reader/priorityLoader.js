/** A stalled page cannot occupy the single-flight slot indefinitely. */
export const IMAGE_LOAD_TIMEOUT_MILLIS = 15_000

/**
 * A single-flight image loader.
 *
 * The loader owns both its queue and the one active image. Cancelling an action or
 * resetting for another item removes the active source as well as its listeners and
 * timer, so obsolete bytes cannot keep downloading behind the next route.
 */
export function createPriorityLoader({
  schedule = queueMicrotask,
  setTimer = setTimeout,
  clearTimer = clearTimeout,
  timeoutMillis = IMAGE_LOAD_TIMEOUT_MILLIS,
} = {}) {
  if (!Number.isFinite(timeoutMillis) || timeoutMillis <= 0) {
    throw new Error('timeoutMillis must be a positive finite number')
  }

  let generation = 0
  let queue = []
  let active = null
  let sequence = 0
  let pumpScheduled = false

  function clearAttempt(task, clearSource) {
    if (task.timer !== null) {
      clearTimer(task.timer)
      task.timer = null
    }
    if (task.onLoad) task.node.removeEventListener('load', task.onLoad)
    if (task.onError) task.node.removeEventListener('error', task.onError)
    task.onLoad = null
    task.onError = null
    if (clearSource) task.node.removeAttribute('src')
  }

  function isCurrent(task, attempt) {
    return (
      active === task &&
      task.generation === generation &&
      !task.cancelled &&
      !task.finished &&
      task.attempt === attempt
    )
  }

  function release(task) {
    if (task.finished) return false
    task.finished = true
    if (active === task) active = null
    pump()
    return true
  }

  function succeed(task, attempt) {
    if (!isCurrent(task, attempt)) return
    clearAttempt(task, false)
    const onSuccess = task.onSuccess
    const url = task.url
    if (release(task)) onSuccess?.(url)
  }

  function fail(task, attempt) {
    if (!isCurrent(task, attempt)) return
    clearAttempt(task, true)
    if (task.retries === 0) {
      task.retries = 1
      schedule(() => {
        if (active === task && !task.cancelled && task.generation === generation) startAttempt(task)
      })
      return
    }
    const onFailure = task.onFailure
    const url = task.url
    if (release(task)) onFailure?.(url)
  }

  function startAttempt(task) {
    if (active !== task || task.cancelled || task.generation !== generation) return
    const attempt = ++task.attempt
    task.onLoad = () => succeed(task, attempt)
    task.onError = () => fail(task, attempt)
    task.node.addEventListener('load', task.onLoad)
    task.node.addEventListener('error', task.onError)
    task.node.src = task.url
    task.timer = setTimer(() => fail(task, attempt), timeoutMillis)
    if (task.node.complete) {
      schedule(() =>
        task.node.naturalWidth > 0 ? succeed(task, attempt) : fail(task, attempt),
      )
    }
  }

  function pump() {
    if (pumpScheduled) return
    pumpScheduled = true
    schedule(() => {
      pumpScheduled = false
      if (active) return
      queue.sort((left, right) => left.priority - right.priority || left.sequence - right.sequence)
      const task = queue.shift()
      if (!task) return
      if (task.cancelled || task.generation !== generation) {
        pump()
        return
      }
      active = task
      task.started = true
      startAttempt(task)
    })
  }

  function cancel(task) {
    if (!task || task.finished || task.cancelled) return
    task.cancelled = true
    queue = queue.filter((candidate) => candidate !== task)
    if (active === task) {
      clearAttempt(task, true)
      active = null
    }
    task.finished = true
    pump()
  }

  function clearAll() {
    generation += 1
    for (const task of queue) {
      task.cancelled = true
      task.finished = true
    }
    queue = []
    if (active) {
      const task = active
      active = null
      task.cancelled = true
      clearAttempt(task, true)
      task.finished = true
    }
  }

  return {
    reset: clearAll,
    destroy: clearAll,

    get pending() {
      return queue.length
    },

    /**
     * A Svelte action that queues one image and updates it without restarting the same URL.
     *
     * @param {HTMLImageElement} node
     * @param {{url: string, priority: number, onSuccess?: (url: string) => void, onFailure?: (url: string) => void}} initial
     */
    load(node, initial) {
      let task

      function enqueue(config) {
        task = {
          node,
          url: config.url,
          priority: config.priority,
          onSuccess: config.onSuccess,
          onFailure: config.onFailure,
          sequence: sequence++,
          generation,
          started: false,
          finished: false,
          cancelled: false,
          retries: 0,
          attempt: 0,
          timer: null,
          onLoad: null,
          onError: null,
        }
        queue.push(task)
        pump()
      }

      enqueue(initial)

      return {
        update(config) {
          if (task.url === config.url) {
            task.priority = config.priority
            task.onSuccess = config.onSuccess
            task.onFailure = config.onFailure
            pump()
            return
          }
          cancel(task)
          node.removeAttribute('src')
          enqueue(config)
        },
        destroy() {
          cancel(task)
        },
      }
    },
  }
}
