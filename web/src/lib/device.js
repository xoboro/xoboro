const STORAGE_KEY = 'xoboro.device'

/**
 * A stable identity for this browser, sent with read progress.
 *
 * Not a secret and not an authenticator: the server records it so a reader can tell
 * which device last moved their place. It is stored in plain `localStorage` for
 * exactly that reason — nothing is protected by it, so nothing is lost if it is read
 * or edited.
 *
 * Generated once and reused, because a fresh identity on every reload would make the
 * progress history read as a different device each time.
 */
export function deviceIdentity(storage = globalThis.localStorage) {
  const stored = read(storage)
  if (stored) return stored

  const identity = {
    deviceId: newIdentifier(),
    deviceName: describe(),
  }
  try {
    storage?.setItem(STORAGE_KEY, JSON.stringify(identity))
  } catch {
    // Private browsing can refuse storage outright. A per-session identity is worse
    // than a persistent one but better than failing to record progress at all.
  }
  return identity
}

function read(storage) {
  try {
    const raw = storage?.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    // User-writable storage, so a malformed value is discarded rather than trusted.
    return typeof parsed?.deviceId === 'string' && parsed.deviceId ? parsed : null
  } catch {
    return null
  }
}

function newIdentifier() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID()
  // A fallback for a context without crypto. Collision risk is irrelevant: this
  // labels a device in a progress row, it does not authorize anything.
  return `device-${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`
}

function describe() {
  const platform = globalThis.navigator?.platform
  return platform ? `Xoboro web (${platform})` : 'Xoboro web'
}
