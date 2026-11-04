import { session } from './session.js'

/** Read-after-write ordering for this tab. Null scope means a whole-series mutation. */
export function createProgressWriteBarrier() {
  const pending = new Map()
  let generation = 0

  function matching(itemId) {
    return [...pending].filter(([id]) => itemId == null || id == null || id === itemId)
      .flatMap(([, writes]) => [...writes])
  }

  function aborted(signal) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  }

  async function settle(itemId, signal) {
    const started = generation
    while (matching(itemId).length) {
      aborted(signal)
      const writes = Promise.allSettled(matching(itemId))
      await new Promise((resolve, reject) => {
        const onAbort = () => {
          signal.removeEventListener('abort', onAbort)
          reject(new DOMException('Aborted', 'AbortError'))
        }
        signal?.addEventListener('abort', onAbort, { once: true })
        writes.then(resolve).finally(() => signal?.removeEventListener('abort', onAbort))
      })
      if (started !== generation) throw new DOMException('Session changed', 'AbortError')
    }
    aborted(signal)
  }

  return {
    clear() { generation += 1; pending.clear() },
    track(itemId, operation) {
      let writes = pending.get(itemId)
      if (!writes) pending.set(itemId, (writes = new Set()))
      writes.add(operation)
      const done = () => {
        writes.delete(operation)
        if (pending.get(itemId) === writes && writes.size === 0) pending.delete(itemId)
      }
      operation.then(done, done)
      return operation
    },
    wait(itemId = null, { signal } = {}) {
      aborted(signal)
      return matching(itemId).length ? settle(itemId, signal) : null
    },
  }
}

export const progressWrites = createProgressWriteBarrier()
session.subscribe(() => progressWrites.clear())
