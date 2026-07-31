import { request } from '../http.js'

/**
 * The remaining administrator surfaces: users, settings, activity, duplicates and
 * external login.
 *
 * Grouped in one module because each is two or three calls; splitting them into
 * five files of four lines would be organisation for its own sake.
 */

// ── users ──────────────────────────────────────────────────────────────────────

export function listUsers() {
  return request('/users')
}

export function createUser(user) {
  return request('/users', { method: 'POST', body: user })
}

/**
 * Replaces a user's authorization exactly as submitted.
 *
 * Unknown role names and invalid library identifiers are rejected with
 * `400 invalid_request` and change nothing — deliberately not dropped, because a
 * mistyped role would otherwise grant fewer rights than asked for while answering
 * with success.
 */
export function replaceUser(userId, user) {
  return request(`/users/${userId}`, { method: 'PUT', body: user })
}

/**
 * Resets another user's password.
 *
 * Takes only `newPassword` — an administrator does not know the target's current
 * one. Like a self-service change, it invalidates every session that user holds.
 */
export function resetUserPassword(userId, newPassword) {
  return request(`/users/${userId}/password`, { method: 'PUT', body: { newPassword } })
}

/**
 * Deletes a user.
 *
 * The server refuses two cases that the UI must therefore be able to explain rather
 * than merely fail on: `cannot_delete_own_account`, and `last_administrator_protected`
 * when the request would remove the only remaining administrator.
 */
export function deleteUser(userId) {
  return request(`/users/${userId}`, { method: 'DELETE' })
}

// ── api keys (self-service) ────────────────────────────────────────────────────

/**
 * API keys are self-service only; there is no administrator surface for them.
 *
 * That separation is the point: no route accepts a user identifier, so a caller
 * cannot select or act on someone else's key.
 */
export function listApiKeys() {
  return request('/me/api-keys')
}

/**
 * Creates an API key.
 *
 * The plaintext `token` is in the response **once**. Xoboro stores a digest, so it
 * cannot be retrieved again — the UI has to show it at creation time and say so.
 *
 * `scopes` narrows the key to a subset of the caller's own roles; omitting it leaves
 * the key as capable as its owner. `expiresAtMillis` is an absolute instant, not a
 * duration, and must be in the future.
 */
export function createApiKey({ comment, scopes = null, expiresAtMillis = null }) {
  return request('/me/api-keys', {
    method: 'POST',
    body: {
      comment,
      ...(scopes?.length ? { scopes } : {}),
      ...(expiresAtMillis ? { expiresAtMillis } : {}),
    },
  })
}

export function deleteApiKey(apiKeyId) {
  return request(`/me/api-keys/${apiKeyId}`, { method: 'DELETE' })
}

// ── settings ───────────────────────────────────────────────────────────────────

export function readServerSettings() {
  return request('/server-settings')
}

/**
 * Writes server settings.
 *
 * Fields absent from the body are left unchanged, so the UI sends only what it
 * edited — the opposite of the library `PUT`, and worth keeping straight.
 */
export function writeServerSettings(settings) {
  return request('/server-settings', { method: 'PUT', body: settings })
}

/**
 * Settings that only take effect after a restart.
 *
 * The API deliberately reports no boolean "restart required": for these it returns
 * `databaseSource` and `effectiveValue`, and their difference *is* the answer. A
 * derived flag could disagree with the two values it was derived from.
 */
export const RESTART_REQUIRED_SETTINGS = Object.freeze([
  'serverPort',
  'serverContextPath',
  'kepubifyPath',
])

/**
 * Whether a multi-source setting has a stored value that is not the running one.
 *
 * @param {{databaseSource: unknown, effectiveValue: unknown}|undefined} setting
 */
export function awaitingRestart(setting) {
  if (!setting || typeof setting !== 'object') return false
  if (!('databaseSource' in setting) || !('effectiveValue' in setting)) return false
  // A stored value of null means "no override", which is not a pending change.
  if (setting.databaseSource === null || setting.databaseSource === undefined) return false
  return String(setting.databaseSource) !== String(setting.effectiveValue)
}

// ── activity ───────────────────────────────────────────────────────────────────

export function listAuthenticationActivity({ page = 0, size = 50 } = {}) {
  return request('/authentication-activity', { query: { page, size } })
}

export function listHistory({ page = 0, size = 50 } = {}) {
  return request('/history', { query: { page, size } })
}

// ── duplicate pages ────────────────────────────────────────────────────────────

/**
 * Duplicate-page actions.
 *
 * `IGNORE` is the only one with an effect today: the candidate list excludes any
 * hash with a recorded decision, so ignoring one removes it permanently.
 *
 * The two delete actions are stored as **stated intent**. Nothing executes them —
 * removing a page means rewriting an archive on disk, which is destructive and
 * irreversible for files the operator owns. `deleteCount` is therefore always `0`,
 * and that is the stored value rather than a placeholder.
 */
export const DUPLICATE_ACTIONS = Object.freeze(['IGNORE', 'DELETE_MANUAL', 'DELETE_AUTO'])

/** Actions the server records but does not carry out. */
export const UNPERFORMED_ACTIONS = Object.freeze(['DELETE_MANUAL', 'DELETE_AUTO'])

export function listDuplicateCandidates() {
  return request('/duplicate-pages')
}

export function listDuplicateDecisions() {
  return request('/duplicate-pages/decided')
}

export function listHashCarriers(pageHash) {
  return request(`/duplicate-pages/${encodeURIComponent(pageHash)}/media-items`)
}

export function recordDuplicateDecision(pageHash, action, sizeBytes = null) {
  if (!DUPLICATE_ACTIONS.includes(action)) throw new Error(`unknown action: ${action}`)
  return request(`/duplicate-pages/${encodeURIComponent(pageHash)}`, {
    method: 'PUT',
    body: { action, ...(sizeBytes === null ? {} : { sizeBytes }) },
  })
}

// ── external login ─────────────────────────────────────────────────────────────

/**
 * Reads the resolved OAuth2/OIDC configuration.
 *
 * Read-only by design. Registration lives in environment variables so client secrets
 * never enter the database — the one artifact that gets backed up, copied elsewhere
 * to debug, and restored onto other hosts. No client id, secret or endpoint is
 * returned; publishing those would turn a configuration display into a credential
 * disclosure.
 */
export function readExternalLogin() {
  return request('/authentication/oauth2')
}
