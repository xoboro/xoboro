<script>
  /**
   * Drives Dialog from a real opener so focus restore can be asserted.
   *
   * A test cannot assert "focus returned to the control that opened it" by rendering
   * the dialog directly — there would be no opener. This harness supplies one, plus
   * the two shapes that matter: a dialog with focusable content, and a bare one with
   * none.
   */
  import Dialog from '../../src/components/Dialog.svelte'

  let {
    open = false,
    onclose = null,
    bare = false,
    danger = false,
    /** Renders an action that only becomes focusable once the field is filled. */
    lockedAction = false,
  } = $props()

  let visible = $state(open)
  let typed = $state('')

  function close() {
    visible = false
    onclose?.()
  }
</script>

<button data-testid="opener" type="button" onclick={() => (visible = true)}>open</button>

{#if visible}
  <Dialog title="Harness dialog" onclose={close} {danger}>
    {#snippet children()}
      {#if bare}
        <p>Nothing here can hold focus.</p>
      {:else}
        <label for="harness-field">Field</label>
        <input id="harness-field" bind:value={typed} />
        <button type="button">Secondary</button>
        {#if lockedAction}
          <button data-testid="locked" type="button" disabled={typed !== 'unlock'}>Confirm</button>
        {/if}
      {/if}
    {/snippet}
  </Dialog>
{/if}
