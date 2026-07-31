import { fireEvent, render, screen } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import TypedConfirmDialog from '../src/admin/TypedConfirmDialog.svelte'

function open(props = {}) {
  return render(TypedConfirmDialog, {
    title: 'Synthetic confirmation',
    expected: 'Synthetic Library',
    summary: 'This destroys 12 series and 340 items.',
    actionLabel: 'Delete',
    onconfirm: vi.fn(),
    onclose: vi.fn(),
    ...props,
  })
}

/**
 * The console's gate on anything unrecoverable, tested directly.
 *
 * Every screen that uses it exercises the happy path, which is why the failure below
 * survived: no screen passes a blank `expected`, so nothing reached the case where the
 * gate opens by itself. A shared safety component needs its own tests precisely for the
 * inputs its current callers never send.
 */
describe('TypedConfirmDialog', () => {
  it('stays shut when there is nothing to type', async () => {
    // `'' === ''` is true, so a blank expectation enabled the destructive button with an
    // empty field — the entire gate gone, silently. It has to fail closed: unsatisfiable
    // is the right answer, because a confirmation with nothing to confirm is not one.
    open({ expected: '' })

    expect(screen.getByTestId('typed-confirm')).toBeDisabled()
  })

  it('stays shut for a null expectation too', async () => {
    // Worth stating what this does and does not prove. Removing the emptiness guard does
    // *not* fail it — `'' === null` is false, so a null expectation is unsatisfiable by
    // accident either way. What it guards is the plausible repair of the hole above,
    // `typed === (expected ?? '')`, which turns a missing expectation back into an open
    // gate. It is here for that variant, not as a second witness for the first case.
    open({ expected: null })

    expect(screen.getByTestId('typed-confirm')).toBeDisabled()
  })

  it('compares exactly, without trimming or folding case', async () => {
    const { container } = open()
    const field = container.querySelector('.panel input')
    const confirm = screen.getByTestId('typed-confirm')

    for (const near of ['synthetic library', 'SYNTHETIC LIBRARY', ' Synthetic Library', 'Synthetic Library ']) {
      await fireEvent.input(field, { target: { value: near } })
      expect(confirm, `accepted ${JSON.stringify(near)}`).toBeDisabled()
    }

    await fireEvent.input(field, { target: { value: 'Synthetic Library' } })
    expect(confirm).not.toBeDisabled()
  })

  it('does not let Enter in the field stand in for the button', async () => {
    // The field is not inside a form and every button is type="button", so a stray Enter
    // cannot commit a destructive action the operator was still reading about.
    const onconfirm = vi.fn()
    const { container } = open({ onconfirm })
    const field = container.querySelector('.panel input')

    await fireEvent.input(field, { target: { value: 'Synthetic Library' } })
    await fireEvent.keyDown(field, { key: 'Enter' })

    expect(onconfirm).not.toHaveBeenCalled()
    expect(container.querySelector('form')).toBeNull()
  })

  it('refuses a second press while the first is in flight', async () => {
    const onconfirm = vi.fn()
    open({ busy: true, onconfirm })

    // Disabled even though nothing has been typed, and disabled again once it has: busy
    // is not a state a double-click should be able to slip past.
    expect(screen.getByTestId('typed-confirm')).toBeDisabled()
  })

  it('states the blast radius it was given', async () => {
    open()

    expect(screen.getByTestId('confirm-summary').textContent).toContain('12')
    expect(screen.getByTestId('confirm-summary').textContent).toContain('340')
  })
})
