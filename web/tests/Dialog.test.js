import { fireEvent, render, screen } from '@testing-library/svelte'
import { describe, expect, it, vi } from 'vitest'
import DialogHarness from './helpers/DialogHarness.svelte'

/**
 * The focus assertions here are the point of the component.
 *
 * The codebase this UI grew from had a dialog that set role, aria-modal, Escape and
 * a scroll lock but never moved focus into itself or restored it — so a keyboard
 * user was left behind a modal they could not reach. Every destructive confirmation
 * in the console goes through this component, and a confirmation nobody can reach by
 * keyboard is a confirmation that gets clicked past.
 */
describe('Dialog', () => {
  it('is an accessible modal labelled by its heading', () => {
    render(DialogHarness, { open: true })

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')

    const labelId = dialog.getAttribute('aria-labelledby')
    expect(labelId).toBeTruthy()
    expect(document.getElementById(labelId)).toBe(screen.getByRole('heading', { level: 2 }))
  })

  it('moves focus into the dialog on open', () => {
    const { container } = render(DialogHarness, { open: true })

    // The first focusable inside the panel, not the backdrop and not the body.
    const first = container.querySelector('.panel button, .panel input')
    expect(document.activeElement).toBe(first)
  })

  it('always has somewhere to put focus, even with only text inside', () => {
    // The close button is what guarantees this. A dialog whose body is only text
    // must still take focus, or a screen reader announces nothing when it opens —
    // and this is the case where it would be easiest to end up focusing nothing.
    const { container } = render(DialogHarness, { open: true, bare: true })

    expect(document.activeElement).toBe(container.querySelector('.panel .close'))
    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  })

  it('returns focus to the control that opened it', async () => {
    const { container } = render(DialogHarness, { open: false })

    const opener = screen.getByTestId('opener')
    opener.focus()
    await fireEvent.click(opener)
    expect(document.activeElement).not.toBe(opener)

    await fireEvent.click(container.querySelector('.panel .close'))
    expect(document.activeElement).toBe(opener)
  })

  it('wraps Tab from the last focusable back to the first', async () => {
    const { container } = render(DialogHarness, { open: true })

    const nodes = [...container.querySelectorAll('.panel button, .panel input')]
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    expect(nodes.length).toBeGreaterThan(1)

    last.focus()
    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab' })
    expect(document.activeElement).toBe(first)
  })

  it('wraps Shift+Tab from the first focusable back to the last', async () => {
    const { container } = render(DialogHarness, { open: true })

    const nodes = [...container.querySelectorAll('.panel button, .panel input')]
    nodes[0].focus()
    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(nodes[nodes.length - 1])
  })

  it('keeps Tab inside even when the close button is the only control', async () => {
    // The narrowest trap there is: one focusable element, so Tab must wrap to
    // itself rather than walking out into the page behind the modal.
    const { container } = render(DialogHarness, { open: true, bare: true })

    const close = container.querySelector('.panel .close')
    close.focus()
    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab' })
    expect(document.activeElement).toBe(close)

    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(close)
  })

  it('closes on Escape', async () => {
    const onclose = vi.fn()
    render(DialogHarness, { open: true, onclose })

    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onclose).toHaveBeenCalled()
  })

  it('closes on a backdrop click', async () => {
    const onclose = vi.fn()
    const { container } = render(DialogHarness, { open: true, onclose })

    await fireEvent.click(container.querySelector('.backdrop'))
    expect(onclose).toHaveBeenCalled()
  })

  it('gives the backdrop an accessible name rather than being a bare click target', () => {
    const { container } = render(DialogHarness, { open: true })

    const backdrop = container.querySelector('.backdrop')
    expect(backdrop.tagName).toBe('BUTTON')
    expect(backdrop).toHaveAttribute('aria-label')
  })

  it('locks body scroll while open and restores it on close', async () => {
    document.body.style.overflow = 'scroll'
    const { container } = render(DialogHarness, { open: true })

    expect(document.body.style.overflow).toBe('hidden')

    await fireEvent.click(container.querySelector('.panel .close'))
    // Restored to what it was, not blanked: the page may have had its own value.
    expect(document.body.style.overflow).toBe('scroll')
    document.body.style.overflow = ''
  })

  it('marks a destructive dialog on the surface, not only on its button', async () => {
    const { container } = render(DialogHarness, { open: true, danger: true })

    expect(container.querySelector('.panel').classList.contains('danger')).toBe(true)
  })

  it('re-reads the focusable set so a newly enabled control joins the trap', async () => {
    // A confirmation dialog enables its destructive button only once the resource
    // name is typed. A cached focus list would trap Tab on stale nodes and leave the
    // button unreachable by keyboard — the exact control that most needs reaching.
    const { container } = render(DialogHarness, { open: true, lockedAction: true })

    const locked = container.querySelector('[data-testid="locked"]')
    expect(locked).toBeDisabled()

    await fireEvent.input(container.querySelector('.panel input'), { target: { value: 'unlock' } })
    expect(locked).not.toBeDisabled()

    locked.focus()
    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab' })
    const nodes = [...container.querySelectorAll('.panel button, .panel input')]
    expect(document.activeElement).toBe(nodes[0])
  })
})
