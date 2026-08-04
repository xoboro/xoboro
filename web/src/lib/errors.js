/**
 * The native error envelope is `{ "code", "message" }`, and clients must branch
 * on `code` — `message` is human-readable server prose, not a contract.
 *
 * This module is the only place that knows what a code means to the UI. Screens
 * branch on `treatment`, never on a status number and never on the text.
 */

/**
 * How a screen should react. Anything not listed here is `UNKNOWN`, which is a
 * generic failure message — deliberately not a crash, because the server is
 * allowed to add codes without that being a breaking change.
 */
export const Treatment = Object.freeze({
  /** The session is gone. Drop to the login gate and keep the intended route. */
  REAUTHENTICATE: 'REAUTHENTICATE',
  /** Login throttled. Show the wait and disable submit until it passes. */
  THROTTLED: 'THROTTLED',
  /** A newer read position exists. Re-read and ask; never resolve silently. */
  PROGRESS_CONFLICT: 'PROGRESS_CONFLICT',
  /** Refused because the library storage is unreachable. Offer a re-check. */
  LIBRARY_UNAVAILABLE: 'LIBRARY_UNAVAILABLE',
  /** A specific input is wrong. Render inline on that field, not as a toast. */
  FIELD: 'FIELD',
  /** The whole submission is malformed rather than one field. */
  FORM: 'FORM',
  /** Setup already happened. Go to login. */
  SETUP_DONE: 'SETUP_DONE',
  /** The affordance should not have rendered. Re-read the session. */
  FORBIDDEN: 'FORBIDDEN',
  /** The thing is not there — usually a stale list the caller should refresh. */
  NOT_FOUND: 'NOT_FOUND',
  /** The event stream is at capacity. Retry after the given delay. */
  STREAM_AT_CAPACITY: 'STREAM_AT_CAPACITY',
  /** The request never reached the server, or the reply was not JSON. */
  OFFLINE: 'OFFLINE',
  UNKNOWN: 'UNKNOWN',
})

/**
 * Codes that identify one responsible input, mapped to the field that owns it.
 *
 * Rendering these inline matters: a toast saying "library root overlap" leaves
 * the operator to guess which of the two fields on the form is wrong.
 */
export const FIELD_FOR_CODE = Object.freeze({
  library_root_missing: 'source.location',
  library_root_not_directory: 'source.location',
  library_root_overlap: 'source.location',
  library_name_conflict: 'name',
  invalid_credentials: 'password',
})

const TREATMENT_FOR_CODE = Object.freeze({
  authentication_required: Treatment.REAUTHENTICATE,
  rate_limit_exceeded: Treatment.THROTTLED,
  stale_progress: Treatment.PROGRESS_CONFLICT,
  library_unavailable: Treatment.LIBRARY_UNAVAILABLE,
  server_already_claimed: Treatment.SETUP_DONE,
  event_stream_capacity: Treatment.STREAM_AT_CAPACITY,
  invalid_request: Treatment.FORM,
  // A rejected query is a client defect, not something the user can correct, so
  // it gets the generic treatment and is worth logging loudly.
  invalid_query: Treatment.UNKNOWN,
})

/**
 * Every code this UI is prepared to name, plus the two generic ones the server
 * falls back to and this client's own transport failure.
 *
 * Exported so a test can assert the message catalog covers all of them. A code
 * with no catalog entry still renders — as the generic message — but silently, and
 * a user seeing "something went wrong" for a condition we understand is a
 * regression nobody notices without this list.
 */
export const KNOWN_CODES = Object.freeze([
  ...Object.keys(TREATMENT_FOR_CODE),
  ...Object.keys(FIELD_FOR_CODE),
  'forbidden',
  'not_found',
  'network_unreachable',
  'unknown',
])

/** A failure from the native API, carrying its stable `code`. */
export class XoboroApiError extends Error {
  /**
   * @param {object} init
   * @param {number} init.status HTTP status.
   * @param {string} init.code the stable native code.
   * @param {string} [init.serverMessage] English server prose. For logs only.
   * @param {number|null} [init.retryAfterSeconds] parsed `Retry-After`.
   */
  constructor({ status, code, serverMessage = '', retryAfterSeconds = null }) {
    // The Error message is for a developer reading a stack trace. It is never
    // what a user sees: `messageKey` is, and it goes through the catalog.
    super(`${code} (${status})`)
    this.name = 'XoboroApiError'
    this.status = status
    this.code = code
    this.serverMessage = serverMessage
    this.retryAfterSeconds = retryAfterSeconds
  }

  /** How the calling screen should react. */
  get treatment() {
    const known = TREATMENT_FOR_CODE[this.code]
    if (known) return known
    if (this.code in FIELD_FOR_CODE) return Treatment.FIELD
    // Every native authorization and lookup failure ends in these suffixes
    // (`library_administration_forbidden`, `media_item_not_found`, ...), so the
    // suffix is the contract rather than an enumeration that goes stale.
    if (this.code.endsWith('_forbidden') || this.code === 'forbidden') return Treatment.FORBIDDEN
    if (this.code.endsWith('_not_found') || this.code === 'not_found') return Treatment.NOT_FOUND
    return Treatment.UNKNOWN
  }

  /** The field this error belongs to, or `null` if it is not field-scoped. */
  get field() {
    return FIELD_FOR_CODE[this.code] ?? null
  }

  /**
   * The catalog key for user-facing text.
   *
   * The server's own `message` is never rendered: it is English prose and this
   * UI ships in Korean and English.
   */
  get messageKey() {
    return `errors.${this.code}`
  }

  /**
   * The key to render when [messageKey] has no catalog entry.
   *
   * Without this, every code the catalog has not named individually rendered as
   * "something went wrong" — including the seventeen `*_forbidden` codes the
   * server sends, which have a perfectly good shared message. A reader denied a
   * page was told the server had a problem rather than that they lacked
   * permission, and an operator who had just created a user got the same.
   *
   * The suffix is the contract here for the same reason it is in [treatment]: the
   * catalog naming a code individually is an improvement on this, never a
   * requirement, so adding a server code cannot silently degrade the message.
   */
  get fallbackMessageKey() {
    if (this.code.endsWith('_forbidden') || this.code === 'forbidden') return 'errors.forbidden'
    if (this.code.endsWith('_not_found') || this.code === 'not_found') return 'errors.not_found'
    return 'errors.unknown'
  }
}

/** A request that never got an answer. Distinct from a rejection by the server. */
export class XoboroNetworkError extends Error {
  constructor(cause) {
    super('the request did not reach the server')
    this.name = 'XoboroNetworkError'
    this.cause = cause
    this.treatment = Treatment.OFFLINE
    this.code = 'network_unreachable'
    this.messageKey = 'errors.network_unreachable'
    this.fallbackMessageKey = 'errors.unknown'
  }
}

/**
 * Parses `Retry-After`, which is either a delay in seconds or an HTTP date.
 *
 * @param {string|null} header
 * @param {number} nowMillis
 * @returns {number|null} whole seconds to wait, or null if unusable.
 */
export function parseRetryAfter(header, nowMillis) {
  if (!header) return null
  const trimmed = header.trim()
  if (/^\d+$/.test(trimmed)) return Number(trimmed)
  const at = Date.parse(trimmed)
  if (Number.isNaN(at)) return null
  // A date already in the past means "retry now", not "wait a negative amount".
  return Math.max(0, Math.ceil((at - nowMillis) / 1000))
}
