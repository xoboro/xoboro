import { request } from '../http.js'

/** Operational metrics, the task queue, and backups. All administrator-only. */

export function readMetrics() {
  return request('/metrics')
}

export function readTaskQueue() {
  return request('/tasks')
}

/**
 * The two queue-clearing routes, each honest about which state it targets.
 *
 * They are separate on purpose and must stay separate in the UI. `unclaimed`
 * discards queued work no worker has taken; `dead` removes rows that permanently
 * failed so their attempt budget starts over on the next enqueue. Offering one
 * button labelled "clear the queue" would throw away whichever the operator did not
 * mean.
 *
 * Both answer `200` with the number removed rather than `204`, because for someone
 * clearing a backlog the count is the useful part of the reply.
 */
export const TASK_STATES = Object.freeze(['unclaimed', 'dead'])

export function discardTasks(state) {
  if (!TASK_STATES.includes(state)) throw new Error(`unknown task state: ${state}`)
  return request(`/tasks/${state}`, { method: 'DELETE' })
}

export function listBackups() {
  return request('/backups')
}

/**
 * Creates a backup.
 *
 * Synchronous, answering `201` with the descriptor once done: it is a single
 * `VACUUM INTO` plus an integrity check, not a filesystem walk, so unlike a library
 * scan there is nothing to enqueue.
 */
export function createBackup() {
  return request('/backups', { method: 'POST' })
}

export function deleteBackup(backupId) {
  return request(`/backups/${backupId}`, { method: 'DELETE' })
}

/**
 * Restoring is deliberately absent.
 *
 * It requires taking the live database file offline, which conflicts with the
 * running server's own lock, so it is an operator action through the CLI. The
 * backups screen names the command rather than hiding that the capability exists.
 */
export const RESTORE_COMMAND = 'xoboro restore <path>'
