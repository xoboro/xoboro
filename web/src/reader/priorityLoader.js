/**
 * A single-flight image loader.
 *
 * The reason this exists rather than plain `<img src>`: a browser given fifty image
 * URLs at once opens as many connections as it is willing to and the page the reader
 * is actually looking at competes with forty-nine prefetches. On a long chapter that
 * looks like a slow server. Here exactly one request is in flight, and the queue is
 * re-sorted every time a priority changes, so the current page always goes next.
 *
 * Ported from the base UI, where it was the single most valuable piece of the reader.
 */

export function createPriorityLoader({ schedule = queueMicrotask } = {}) {
  /** Bumped to invalidate everything queued for a previous media item. */
  let generation = 0
  let queue = []
  let inFlight = 0
  let sequence = 0
  let pumpScheduled = false

  function pump() {
    if (pumpScheduled) return
    pumpScheduled = true
    schedule(() => {
      pumpScheduled = false
      // Sorted at pump time, not at insert time: a task's priority changes as the
      // reader moves, and a queue ordered on insertion would keep serving the page
      // they have already left.
      queue.sort((left, right) => left.priority - right.priority || left.sequence - right.sequence)
      if (inFlight || queue.length === 0) return

      const task = queue.shift()
      if (task.cancelled || task.generation !== generation) {
        pump()
        return
      }
      task.started = true
      inFlight = 1

      const finish = () => {
        if (task.finished) return
        task.finished = true
        task.node.removeEventListener('load', finish)
        task.node.removeEventListener('error', finish)
        // Only release the slot if this task still belongs to the current generation;
        // otherwise a stale completion would let two loads run at once.
        if (task.generation === generation) inFlight = 0
        pump()
      }
      task.finish = finish
      task.node.addEventListener('load', finish)
      task.node.addEventListener('error', finish)
      task.node.src = task.url
      // A cached image can be complete the moment src is set, and then no load event
      // ever fires — the queue would stall on a task that already finished.
      if (task.node.complete) schedule(finish)
    })
  }

  return {
    /** Discards everything queued. Used when the reader opens a different item. */
    reset() {
      generation += 1
      queue = []
      inFlight = 0
    },

    /** For assertions and diagnostics: how many tasks are waiting. */
    get pending() {
      return queue.length
    },

    /**
     * A Svelte action: attaches an image to the queue and keeps its priority current.
     *
     * @param {HTMLImageElement} node
     * @param {{url: string, priority: number}} initial
     */
    load(node, initial) {
      let task

      function enqueue(config) {
        task = {
          node,
          url: config.url,
          priority: config.priority,
          sequence: sequence++,
          generation,
          started: false,
          finished: false,
          cancelled: false,
        }
        queue.push(task)
        pump()
      }

      function cancel() {
        if (!task || task.finished || task.cancelled) return
        task.cancelled = true
        if (task.started) {
          task.node.removeEventListener('load', task.finish)
          task.node.removeEventListener('error', task.finish)
          if (task.generation === generation) inFlight = 0
        }
        pump()
      }

      enqueue(initial)

      return {
        update(config) {
          // Same image, new priority: reprioritise instead of restarting, or scrolling
          // would cancel and re-request every visible page.
          if (task.url === config.url) {
            task.priority = config.priority
            pump()
            return
          }
          cancel()
          node.removeAttribute('src')
          enqueue(config)
        },
        destroy: cancel,
      }
    },
  }
}
