<script>
  /**
   * The search screen as a **deep link**, not as the way into searching.
   *
   * Searching now happens on the home screen, in place: the field is already there, the options
   * expand under it, and the answers replace the shelves without the page being exchanged for a
   * different one. This route stays because a URL that opens the search surface has to keep
   * working — it is linkable, bookmarkable and reachable from history — and because opening it
   * directly is the one case where the reader arrived with no screen behind them.
   *
   * What it holds is the text field and nothing else; the scopes, the options and the results
   * live in {@link AdvancedSearch}, which the home screen mounts as well. One copy, so the two
   * cannot drift into two different searches.
   */
  import { ArrowLeft, Search as SearchIcon } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import AdvancedSearch from './AdvancedSearch.svelte'

  /** Long enough that ordinary typing produces one request, short enough to feel live. */
  const DEBOUNCE_MILLIS = 250

  /** What is in the input. */
  let text = $state('')
  /** What has actually been asked for. Lags {@link text} by the debounce. */
  let submitted = $state('')
  let optionsOpen = $state(false)

  let debounce = null

  function typeQuery(value) {
    text = value
    clearTimeout(debounce)
    debounce = setTimeout(() => {
      submitted = text
    }, DEBOUNCE_MILLIS)
  }

  /**
   * Submitting flushes the debounce rather than forcing a second request.
   *
   * If the pending text is already what was asked, there is nothing new to ask, and re-sending
   * it would spend a request to redraw the same page. Re-reading after a failure is the retry on
   * the error notice, which asks unconditionally.
   *
   * It also opens the options, because the two are one action for a reader: submitting a word
   * and then narrowing what it returned is the same search. Opening rather than toggling — a
   * second submit must not close what the reader is currently reading; the disclosure is how it
   * closes.
   */
  function submit(event) {
    event.preventDefault()
    clearTimeout(debounce)
    submitted = text
    optionsOpen = true
  }
</script>

<header>
  <a class="icon" href="#/" aria-label={$_('common.back')}>
    <ArrowLeft size={18} aria-hidden="true" />
  </a>
  <h1>{$_('search.title')}</h1>
</header>

<form class="query" role="search" onsubmit={submit}>
  <label for="search-query">{$_('search.queryLabel')}</label>
  <div class="row">
    <input
      id="search-query"
      data-testid="search-query"
      type="search"
      autocomplete="off"
      value={text}
      oninput={(event) => typeQuery(event.currentTarget.value)}
    />
    <button class="primary" type="submit" data-testid="search-submit">
      <SearchIcon size={18} aria-hidden="true" />
      <span>{$_('search.submit')}</span>
    </button>
  </div>
</form>

<div class="surface">
  <AdvancedSearch query={submitted} bind:open={optionsOpen} />
</div>

<style>
  header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: max(var(--space-4), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) var(--space-3) var(--gutter-left);
  }
  h1 {
    margin: 0;
    font-size: var(--font-lg);
  }
  .icon {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border-radius: var(--radius-sm);
    color: var(--text);
  }
  .query {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: 0 var(--gutter-right) var(--space-4) var(--gutter-left);
  }
  .query label {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .row {
    display: flex;
    gap: var(--space-2);
  }
  input[type='search'] {
    flex: 1 1 auto;
    min-width: 0;
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
    color: var(--text);
    font: inherit;
    /* 16px floor: iOS Safari zooms the viewport on focus below it, and the resulting layout
       jump cannot be undone in CSS. */
    font-size: var(--font-input);
  }
  .primary {
    display: flex;
    flex: 0 0 auto;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  .surface {
    padding: 0 var(--gutter-right) max(var(--space-5), var(--inset-bottom)) var(--gutter-left);
  }
</style>
