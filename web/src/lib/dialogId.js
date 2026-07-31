let counter = 0

/**
 * A unique suffix for a dialog's `aria-labelledby` target.
 *
 * A counter rather than `Math.random()`: two dialogs must not share an id, but the
 * ids should also be stable across runs so a test failure reads the same way twice.
 */
export function nextDialogId() {
  counter += 1
  return String(counter)
}
