<script>
  /**
   * Creates or replaces a collection or a read list, including member order.
   *
   * `PUT` is a **full replacement** of the name, the ordering flag, the member list and
   * the member order — so every field is sent every time. Omitting members would empty
   * the grouping rather than leave it alone.
   *
   * Members are loaded from the dedicated member route, not from the detail response:
   * detail and list responses deliberately do not embed member identifiers, and the
   * member route is the only thing that returns them in stored order.
   */
  import { _ } from '../lib/i18n.js'
  import {
    GROUPINGS,
    createGrouping,
    listMembers,
    moveMember,
    replaceGrouping,
  } from '../lib/api/groupings.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  let {
    /** `'collection'` or `'readList'`. */
    kind,
    /** The grouping being edited, or `null` to create one. */
    grouping = null,
    onsaved,
    onclose,
  } = $props()

  const shape = GROUPINGS[kind]
  const editing = grouping !== null

  let name = $state(grouping?.name ?? '')
  let summary = $state(grouping?.summary ?? '')
  let ordered = $state(grouping?.ordered ?? true)
  /** `[{ id, label }]` in stored order. */
  let members = $state([])
  let busy = $state(false)
  let error = $state(null)

  $effect(() => {
    if (!editing) return
    // Read through the prop each run rather than through a captured local: Svelte warns
    // that a local reference freezes the initial value, and a re-keyed form would then
    // load the previous grouping's members.
    const id = grouping.id
    listMembers(kind, id, { size: 200 })
      .then((page) => {
        members = page.items.map((item) => ({
          id: item.id,
          label: item.title ?? item.name ?? item.id,
        }))
      })
      .catch((caught) => (error = caught))
  })

  function move(index, delta) {
    const reordered = moveMember(
      members.map((member) => member.id),
      index,
      index + delta,
    )
    members = reordered.map((id) => members.find((member) => member.id === id))
  }

  function remove(index) {
    members = members.filter((_member, at) => at !== index)
  }

  async function submit(event) {
    event.preventDefault()
    if (busy) return
    busy = true
    error = null
    try {
      const values = {
        name: name.trim(),
        ordered,
        memberIds: members.map((member) => member.id),
        summary: summary.trim(),
      }
      const saved = editing
        ? await replaceGrouping(kind, grouping.id, values)
        : await createGrouping(kind, values)
      onsaved(saved)
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }
</script>

<Dialog
  title={editing ? $_(`catalog.${kind}.editTitle`) : $_(`catalog.${kind}.createTitle`)}
  {onclose}
>
  {#snippet children()}
    <form id="grouping-form" onsubmit={submit} novalidate>
      <label for="grouping-name">{$_('catalog.grouping.name')}</label>
      <input id="grouping-name" data-testid="grouping-name" bind:value={name} required />

      {#if shape.hasSummary}
        <label for="grouping-summary">{$_('catalog.grouping.summary')}</label>
        <textarea
          id="grouping-summary"
          rows="3"
          data-testid="grouping-summary"
          bind:value={summary}
        ></textarea>
      {/if}

      <label class="check">
        <input type="checkbox" data-testid="grouping-ordered" bind:checked={ordered} />
        {$_('catalog.grouping.ordered')}
      </label>
      <p class="hint">{$_('catalog.grouping.orderedHint')}</p>

      <fieldset>
        <legend>{$_('catalog.grouping.members')}</legend>
        {#if members.length === 0}
          <p class="hint">{$_('catalog.grouping.noMembers')}</p>
        {/if}
        <ol>
          {#each members as member, index (member.id)}
            <li>
              <span class="label">{member.label}</span>
              <div class="member-actions">
                <!-- Buttons rather than drag-and-drop: reordering has to be reachable
                     by keyboard, and a drag handle alone is not. -->
                <button
                  type="button"
                  disabled={!ordered || index === 0}
                  data-testid={`move-up-${member.id}`}
                  aria-label={$_('catalog.grouping.moveUp')}
                  onclick={() => move(index, -1)}
                >
                  ↑
                </button>
                <button
                  type="button"
                  disabled={!ordered || index === members.length - 1}
                  data-testid={`move-down-${member.id}`}
                  aria-label={$_('catalog.grouping.moveDown')}
                  onclick={() => move(index, 1)}
                >
                  ↓
                </button>
                <button
                  type="button"
                  data-testid={`remove-${member.id}`}
                  aria-label={$_('catalog.grouping.removeMember')}
                  onclick={() => remove(index)}
                >
                  ×
                </button>
              </div>
            </li>
          {/each}
        </ol>
        {#if editing}
          <!-- Stated because the count in the list is the caller's visible count, not
               the stored total, so a member the administrator cannot see is not here
               and would be dropped by a full replacement. -->
          <p class="hint" data-testid="visibility-note">{$_('catalog.grouping.visibilityNote')}</p>
        {/if}
      </fieldset>

      <ErrorNotice {error} />
    </form>
  {/snippet}

  {#snippet footer()}
    <button type="button" onclick={onclose}>{$_('common.cancel')}</button>
    <button
      class="primary"
      type="submit"
      form="grouping-form"
      data-testid="grouping-save"
      disabled={busy}
    >
      {busy ? $_('common.saving') : $_('common.save')}
    </button>
  {/snippet}
</Dialog>

<style>
  form {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }
  label {
    margin-top: var(--space-2);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  input:not([type='checkbox']),
  textarea {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
    font: inherit;
    font-size: var(--font-input);
  }
  input:not([type='checkbox']) {
    min-height: var(--touch-target);
  }
  .check {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    font-weight: 400;
  }
  .hint {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  fieldset {
    margin: var(--space-3) 0 0;
    padding: 0;
    border: 0;
  }
  legend {
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  ol {
    margin: var(--space-2) 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-1) 0;
    border-bottom: 1px solid var(--line-subtle);
    font-size: var(--font-sm);
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .member-actions {
    display: flex;
    flex: 0 0 auto;
    gap: var(--space-1);
  }
  .member-actions button {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    cursor: pointer;
  }
  .member-actions button:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .primary {
    min-height: var(--touch-target);
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  .primary:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
