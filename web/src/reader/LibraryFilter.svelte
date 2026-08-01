<script>
  /**
   * Chooses which libraries a search covers.
   *
   * Checkboxes rather than a multiple `<select>`. A `<select multiple>` needs a
   * modifier chord to deselect and gives no keyboard-only way to discover that, which
   * would put the whole filter behind a mouse.
   *
   * Nothing ticked sends no `libraryId` at all, which the server reads as every
   * library the reader is granted. That is stated as text rather than left implied:
   * an empty group of checkboxes otherwise reads as "no libraries" to someone who has
   * not tried it.
   *
   * Rendered only when there is more than one library to choose between. With one, the
   * control cannot change any result, and a filter that does nothing costs a reader
   * the time it takes to work that out.
   */
  import { _ } from '../lib/i18n.js'

  let {
    /** Libraries visible to this reader, as `/libraries` returns them. */
    libraries,
    /** The currently selected library identifiers. */
    selected,
    /** Called with the new selection. */
    onchange,
  } = $props()

  function toggle(libraryId, checked) {
    onchange(
      checked ? [...selected, libraryId] : selected.filter((each) => each !== libraryId),
    )
  }
</script>

{#if libraries.length > 1}
  <fieldset>
    <legend>{$_('search.filters.library')}</legend>
    {#each libraries as library (library.id)}
      <label>
        <input
          type="checkbox"
          data-testid={`filter-library-${library.id}`}
          value={library.id}
          checked={selected.includes(library.id)}
          onchange={(event) => toggle(library.id, event.currentTarget.checked)}
        />
        <span>{library.name}</span>
      </label>
    {/each}
    {#if selected.length === 0}
      <p class="hint">{$_('search.filters.allLibraries')}</p>
    {/if}
  </fieldset>
{/if}

<style>
  fieldset {
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    padding: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  label {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    font-size: var(--font-sm);
  }
  .hint {
    margin: 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
</style>
