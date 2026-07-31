<script>
  /**
   * The only modal in the application.
   *
   * Exists because the codebase this UI grew from had two dialogs that disagreed:
   * `FilterSheet` set `role="dialog"`, `aria-modal`, an Escape handler and a scroll
   * lock, while the reader's settings panel was a plain fixed `div` with none of
   * them — and even `FilterSheet` never moved focus into itself or restored it,
   * leaving a keyboard user stranded behind a modal they could not reach.
   *
   * Every destructive confirmation in the administrator console goes through here,
   * so getting focus right once matters more than it looks: a confirmation nobody
   * can reach by keyboard is a confirmation that gets clicked past.
   *
   * Mounted only while open — the parent guards it with `{#if}` — so mounting *is*
   * opening, and the lifecycle does the work rather than a reactive `open` prop.
   */
  import { X } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { nextDialogId } from '../lib/dialogId.js'

  let {
    title,
    onclose,
    /** Rendered in the body. */
    children,
    /** Rendered in the footer, typically the actions. */
    footer = null,
    /** Marks the dialog as carrying a destructive choice. */
    danger = false,
  } = $props()

  const titleId = `dialog-title-${nextDialogId()}`

  let panel = $state(null)

  /**
   * Elements that can hold focus right now.
   *
   * Recomputed per keystroke rather than cached: a confirmation dialog enables its
   * destructive button only once the name is typed correctly, so the set changes
   * while the dialog is open and a cached list would trap Tab on stale nodes.
   */
  function focusable() {
    if (!panel) return []
    // Deliberately not filtered by visibility. `offsetParent` is always null in
    // jsdom, so a visibility filter would make every focus assertion in the test
    // suite pass against an empty list — the tests would go green while the trap
    // did nothing. Nothing here hides its own controls; it disables them, which the
    // selector already excludes.
    return [
      ...panel.querySelectorAll(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]),' +
          ' textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ]
  }

  function onKeydown(event) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onclose()
      return
    }
    if (event.key !== 'Tab') return

    const nodes = focusable()
    if (nodes.length === 0) {
      // Nothing to move to, so keep focus on the panel rather than letting Tab
      // walk out into the page behind the modal.
      event.preventDefault()
      panel?.focus()
      return
    }
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    const active = document.activeElement

    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && active === last) {
      event.preventDefault()
      first.focus()
    }
  }

  $effect(() => {
    // Captured before focus moves, so it is the control that opened the dialog
    // rather than whatever happens to be focused when it closes.
    const opener = document.activeElement
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    // The close button in the header is what guarantees there is always something
    // to focus, so a dialog whose body is only text still takes focus and a screen
    // reader announces it. `?? panel` covers the structure changing under us rather
    // than a state reachable today.
    const initial = focusable()[0] ?? panel
    initial?.focus()

    return () => {
      document.body.style.overflow = previousOverflow
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus()
    }
  })
</script>

<div class="layer">
  <!-- A button, not a div with a click handler, so it has an accessible name and
       is reachable rather than being an invisible trap for pointer users only. -->
  <button class="backdrop" type="button" aria-label={$_('common.close')} onclick={onclose}></button>

  <div
    class="panel"
    class:danger
    role="dialog"
    aria-modal="true"
    aria-labelledby={titleId}
    tabindex="-1"
    bind:this={panel}
    onkeydown={onKeydown}
  >
    <header>
      <h2 id={titleId}>{title}</h2>
      <button class="close" type="button" aria-label={$_('common.close')} onclick={onclose}>
        <X size={20} aria-hidden="true" />
      </button>
    </header>

    <div class="body">
      {@render children()}
    </div>

    {#if footer}
      <footer>{@render footer()}</footer>
    {/if}
  </div>
</div>

<style>
  .layer {
    position: fixed;
    inset: 0;
    z-index: 50;
  }
  .backdrop {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: var(--surface-scrim);
  }
  .panel {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    display: flex;
    max-height: min(82dvh, calc(100dvh - var(--inset-top) - var(--space-2)));
    flex-direction: column;
    overflow: hidden;
    border: 1px solid var(--line-strong);
    border-bottom: 0;
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    background: var(--surface-overlay);
    box-shadow: var(--shadow-sheet);
  }
  /* A border, not only a red button inside: the whole surface says what kind of
     decision this is, without relying on colour to be the only signal. */
  .panel.danger {
    border-color: var(--danger);
  }
  header {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-4) var(--space-2);
  }
  h2 {
    margin: 0;
    font-size: var(--font-lg);
  }
  .close {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    flex: 0 0 auto;
    padding: 0;
    place-items: center;
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--surface-control);
    color: var(--text);
    cursor: pointer;
  }
  .body {
    overflow-y: auto;
    padding: 0 var(--space-4) var(--space-4);
    overscroll-behavior: contain;
  }
  footer {
    display: flex;
    flex: 0 0 auto;
    justify-content: flex-end;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4) max(var(--space-4), var(--inset-bottom));
    border-top: 1px solid var(--line);
    background: var(--surface-overlay);
  }

  @media (min-width: 700px) {
    .panel {
      top: 50%;
      right: auto;
      bottom: auto;
      left: 50%;
      width: min(520px, calc(100vw - var(--space-6)));
      max-height: min(720px, calc(100dvh - var(--space-7)));
      transform: translate(-50%, -50%);
      border-bottom: 1px solid var(--line-strong);
      border-radius: var(--radius-lg);
    }
    .panel.danger {
      border-color: var(--danger);
    }
  }
</style>
