<script>
  /**
   * One facet's choices, as chips dense enough to read at a glance.
   *
   * A facet is unbounded: it enumerates whatever the library happens to contain, and a
   * real one contains far more than a form can list. Rendered as a column of
   * touch-target-height checkbox rows, one library's genres and tags came to roughly a
   * hundred rows - about 4,400px of filter stacked above the results, so the search
   * screen showed its controls and none of its answers. The control was not wrong, only
   * unbounded; a facet needs a shape that does not grow linearly with the catalog.
   *
   * Three things bound it, in the order they matter:
   *
   *   - **Chips that wrap.** Six or so per line instead of one per line.
   *   - **A cap, with a way past it.** Only [COLLAPSED_LIMIT] show until asked.
   *   - **A find box** once a group is large enough that reading it is the slow part.
   *
   * Selected values sort to the front, which is what makes the cap safe: a choice the
   * reader has made can never be the one hidden behind "show more". Without that,
   * collapsing would silently conceal an active filter and the results would look wrong
   * for no visible reason.
   *
   * The chip is a real `<input type="checkbox">` with a visually-hidden box, not a
   * `<button aria-pressed>`. Keyboard behaviour, the accessibility tree and the group's
   * `<fieldset>` semantics all come free that way, and `:checked` styles the chip
   * without any state mirrored in script.
   */
  import { _ } from '../lib/i18n.js'

  let {
    /** The listing parameter this facet feeds, e.g. `genre`. */
    parameter,
    /** The group's visible name. */
    label,
    /** Every value the facet offers. */
    values,
    /** The values currently selected. */
    selected = [],
    /** Called with `(value, checked)` when a chip is toggled. */
    ontoggle,
  } = $props()

  /**
   * How many chips show before the group asks to be expanded.
   *
   * Twelve is about two lines at a comfortable reading width, which is enough to see
   * what kind of values a facet holds without the group deciding the page's height.
   */
  const COLLAPSED_LIMIT = 12

  /**
   * When a find box appears.
   *
   * Below this, scanning the chips is faster than typing; above it, the reader is
   * looking for a value they already have in mind.
   */
  const FIND_THRESHOLD = 16

  let expanded = $state(false)
  let needle = $state('')

  const chosen = $derived(new Set(selected ?? []))
  // Selected first. This is what makes the cap safe rather than merely tidy.
  const ordered = $derived([
    ...(values ?? []).filter((value) => chosen.has(value)),
    ...(values ?? []).filter((value) => !chosen.has(value)),
  ])
  const trimmed = $derived(needle.trim().toLowerCase())
  const matching = $derived(
    trimmed ? ordered.filter((value) => String(value).toLowerCase().includes(trimmed)) : ordered,
  )
  // Finding is its own way past the cap, so a search shows everything it matched.
  const visible = $derived(
    expanded || trimmed ? matching : matching.slice(0, COLLAPSED_LIMIT),
  )
  const hiddenCount = $derived(Math.max(0, matching.length - visible.length))
</script>

<fieldset data-testid={`facet-${parameter}`}>
  <legend>
    <span class="name">{label}</span>
    {#if chosen.size > 0}
      <span class="chosen" data-testid={`facet-${parameter}-chosen`}>
        {$_('search.filters.selectedCount', { values: { count: chosen.size } })}
      </span>
    {/if}
  </legend>

  {#if (values ?? []).length >= FIND_THRESHOLD}
    <input
      class="find"
      type="search"
      data-testid={`facet-${parameter}-find`}
      placeholder={$_('search.filters.findInGroup', { values: { group: label } })}
      aria-label={$_('search.filters.findInGroup', { values: { group: label } })}
      bind:value={needle}
    />
  {/if}

  <div class="chips">
    {#each visible as value (value)}
      <label class="chip">
        <input
          type="checkbox"
          {value}
          data-testid={`filter-${parameter}-${value}`}
          checked={chosen.has(value)}
          onchange={(event) => ontoggle(value, event.currentTarget.checked)}
        />
        <span>{value}</span>
      </label>
    {/each}
  </div>

  {#if trimmed && matching.length === 0}
    <p class="none" data-testid={`facet-${parameter}-none`}>
      {$_('search.filters.noMatch')}
    </p>
  {/if}

  {#if hiddenCount > 0}
    <button
      type="button"
      class="more"
      data-testid={`facet-${parameter}-more`}
      onclick={() => (expanded = true)}
    >
      {$_('search.filters.showMore', { values: { count: hiddenCount } })}
    </button>
  {:else if expanded && !trimmed && matching.length > COLLAPSED_LIMIT}
    <button
      type="button"
      class="more"
      data-testid={`facet-${parameter}-less`}
      onclick={() => (expanded = false)}
    >
      {$_('search.filters.showLess')}
    </button>
  {/if}
</fieldset>

<style>
  fieldset {
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    padding: 0;
  }
  .name {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .chosen {
    color: var(--accent-text);
    font-size: var(--font-xs);
  }
  .find {
    width: 100%;
    min-height: var(--touch-target);
    margin: var(--space-2) 0 0;
    padding: 0 var(--space-2);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    /* 16px floor: iOS Safari zooms the viewport on focus below it, and the resulting
       layout jump cannot be undone in CSS. */
    font-size: var(--font-input);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-2);
    margin-top: var(--space-2);
  }
  .chip {
    display: inline-flex;
    align-items: center;
    /* Chips are read, not aimed at one at a time, so the 44px touch floor applies to
       the row rather than to every chip - otherwise a wrapped group is as tall as the
       column it replaced. The horizontal padding keeps each target wide enough. */
    min-height: 2rem;
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-pill);
    background: var(--surface-control);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    line-height: 1;
    cursor: pointer;
  }
  /* Visually hidden, not `display: none`: the input stays focusable, keeps its place in
     the accessibility tree, and remains the thing a test clicks. */
  .chip input {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    border: 0;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .chip:has(input:checked) {
    border-color: var(--accent);
    background: var(--accent);
    color: var(--accent-contrast);
  }
  .chip:has(input:focus-visible) {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .more {
    margin-top: var(--space-2);
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent-text);
    font-size: var(--font-xs);
    cursor: pointer;
  }
  .none {
    margin: var(--space-2) 0 0;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
</style>
