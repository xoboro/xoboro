<script>
  /**
   * The EPUB reader.
   *
   * New work rather than a port — the base had no EPUB reader at all.
   *
   * Reading order comes from `/positions`, **not** from the resource manifest. The
   * manifest is in OPF order, the sequence the packager happened to write, so following
   * it would present chapters in an arbitrary order that looks like a broken file. Each
   * position's `href` is sent to the resource route verbatim; a raw OPF-relative href
   * does not resolve, because resolution is an exact index lookup.
   *
   * Chapters render in an `iframe` because they are user-supplied markup. The server
   * already answers resources with `script-src 'none'; object-src 'none'`, and the
   * `sandbox` attribute here is the second half of that: same-origin content that can
   * style itself but cannot run anything or navigate the reader away.
   *
   * Progress is a Readium locator alongside the page position, which is what the
   * progress endpoint stores.
   */
  import { onDestroy, onMount } from 'svelte'
  import { ChevronLeft, ChevronRight, List, Settings, SlidersHorizontal } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import {
    isRetryableDeliveryFailure,
    listPositions,
    readMediaItem,
    resourceUrlFor,
  } from '../lib/api/catalog.js'
  import { resumePage, writeProgress } from '../lib/api/progress.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  let { params } = $props()

  const PROGRESS_DEBOUNCE_MILLIS = 800
  const FONT_SIZES = ['90', '100', '115', '130']
  const LINE_HEIGHTS = ['1.4', '1.6', '1.8']
  const MARGINS = ['16', '32', '64']

  const stored = (key, fallback) => {
    try {
      return localStorage.getItem(`xoboro.epub.${key}`) ?? fallback
    } catch {
      return fallback
    }
  }
  const remember = (key, value) => {
    try {
      localStorage.setItem(`xoboro.epub.${key}`, value)
    } catch {
      // A refused write costs the preference, not the reading session.
    }
  }

  let fontSize = $state(stored('fontSize', '100'))
  let lineHeight = $state(stored('lineHeight', '1.6'))
  let margin = $state(stored('margin', '32'))

  $effect(() => remember('fontSize', fontSize))
  $effect(() => remember('lineHeight', lineHeight))
  $effect(() => remember('margin', margin))

  let item = $state(null)
  let positions = $state([])
  let at = $state(0)
  let chrome = $state(false)
  let settingsOpen = $state(false)
  let error = $state(null)
  let retryable = $state(false)
  let conflict = $state(null)
  let loadedId = $state('')

  let saveTimer = null
  let pending = null

  const position = $derived(positions[at] ?? null)
  const total = $derived(positions.length)
  const source = $derived(
    loadedId && position ? resourceUrlFor(loadedId, position.href) : null,
  )

  function flushProgress() {
    clearTimeout(saveTimer)
    saveTimer = null
    if (!pending || !loadedId) return
    const write = pending
    pending = null
    writeProgress(loadedId, write)
      .then((result) => {
        if (result) conflict = result.current
      })
      .catch((caught) => (error = caught))
  }

  function noteProgress(index) {
    const entry = positions[index]
    if (!entry || !loadedId) return
    clearTimeout(saveTimer)
    // Both, because the endpoint stores a page position and an opaque locator, and a
    // client that sent only one would lose whichever the other reader relies on.
    pending = {
      page: entry.position,
      locator: {
        href: entry.href,
        locations: {
          progression: entry.progression,
          totalProgression: entry.totalProgression,
          position: entry.position,
        },
      },
    }
    saveTimer = setTimeout(flushProgress, PROGRESS_DEBOUNCE_MILLIS)
  }

  async function load(id) {
    error = null
    retryable = false
    try {
      const [detail, order] = await Promise.all([readMediaItem(id), listPositions(id)])
      item = detail
      positions = order
      loadedId = id
      // Positions are one-based and contiguous, so a stored page maps straight onto an
      // index. resumePage also handles a finished book and a page past the end.
      at = resumePage(detail.readProgress, order.length) - 1
    } catch (caught) {
      error = caught
      // Only one of the three delivery failures is worth retrying, so the offer is made
      // only for that one — a retry button on an encrypted file is a button that will
      // never work.
      retryable = isRetryableDeliveryFailure(caught)
    }
  }

  $effect(() => {
    load(params.id)
  })

  function go(delta) {
    const next = at + delta
    if (next < 0 || next >= positions.length) return
    at = next
    noteProgress(at)
  }

  function fromChrome(event) {
    if (event.altKey || event.ctrlKey || event.metaKey) return true
    const target = event.target
    return (
      target instanceof Element &&
      Boolean(target.closest('input, textarea, select, button, a, [role="dialog"]'))
    )
  }

  function onKeydown(event) {
    if (event.key === 'Escape' && chrome) {
      chrome = false
      settingsOpen = false
      return
    }
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key) || fromChrome(event)) return
    event.preventDefault()
    go(event.key === 'ArrowRight' ? 1 : -1)
  }

  onMount(() => {
    window.addEventListener('keydown', onKeydown)
    return () => window.removeEventListener('keydown', onKeydown)
  })

  onDestroy(flushProgress)

  function acceptConflict() {
    const page = conflict?.page
    conflict = null
    if (!page) return
    at = Math.max(0, Math.min(positions.length - 1, page - 1))
  }
</script>

<button
  class="chrome-toggle"
  type="button"
  data-testid="toggle-chrome"
  aria-expanded={chrome}
  aria-label={$_('reader.controls')}
  onclick={() => {
    chrome = !chrome
    if (!chrome) settingsOpen = false
  }}
>
  <SlidersHorizontal size={18} aria-hidden="true" />
</button>

{#if total > 0}
  <p class="position" role="status" aria-live="polite" data-testid="position">
    {$_('reader.position', { values: { page: at + 1, total } })}
  </p>
{/if}

{#if chrome}
  <div class="topbar">
    <a
      class="ic"
      href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'}
      aria-label={$_('common.back')}
    >
      <ChevronLeft size={22} aria-hidden="true" />
    </a>
    <span class="title">{item?.title ?? ''}</span>
    <button
      class="ic"
      type="button"
      data-testid="open-settings"
      aria-label={$_('common.settings')}
      onclick={() => (settingsOpen = true)}
    >
      <Settings size={20} aria-hidden="true" />
    </button>
  </div>

  <div class="bottombar">
    <span class="pageno">{at + 1} / {total}</span>
    <div class="nav">
      <button
        class="ic"
        type="button"
        data-testid="previous-position"
        disabled={at === 0}
        aria-label={$_('reader.previousItem')}
        onclick={() => go(-1)}
      >
        <ChevronLeft size={22} aria-hidden="true" />
      </button>
      <a
        class="ic"
        href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'}
        aria-label={$_('common.list')}
      >
        <List size={20} aria-hidden="true" />
      </a>
      <button
        class="ic"
        type="button"
        data-testid="next-position"
        disabled={at >= total - 1}
        aria-label={$_('reader.nextItem')}
        onclick={() => go(1)}
      >
        <ChevronRight size={22} aria-hidden="true" />
      </button>
    </div>
  </div>
{/if}

<ErrorNotice {error} onretry={retryable ? () => load(params.id) : null} />

{#if source}
  <div
    class="page"
    style={`--epub-font-size:${fontSize}%; --epub-line-height:${lineHeight}; --epub-margin:${margin}px`}
  >
    <!-- Sandboxed: user-supplied markup, same-origin so it can be styled and read, but
         with scripts and navigation withheld. The server already sends
         script-src 'none'; this is the other half of that decision. -->
    <iframe
      src={source}
      title={item?.title ?? $_('reader.chapter')}
      data-testid="chapter-frame"
      sandbox="allow-same-origin"
      referrerpolicy="no-referrer"
    ></iframe>
  </div>
{/if}

{#if settingsOpen}
  <Dialog title={$_('common.settings')} onclose={() => (settingsOpen = false)}>
    {#snippet children()}
      <fieldset>
        <legend>{$_('reader.fontSize')}</legend>
        <div class="options">
          {#each FONT_SIZES as value (value)}
            <button
              type="button"
              class:on={fontSize === value}
              data-testid={`font-${value}`}
              aria-pressed={fontSize === value}
              onclick={() => (fontSize = value)}
            >
              {value}%
            </button>
          {/each}
        </div>
      </fieldset>

      <fieldset>
        <legend>{$_('reader.lineHeight')}</legend>
        <div class="options">
          {#each LINE_HEIGHTS as value (value)}
            <button
              type="button"
              class:on={lineHeight === value}
              aria-pressed={lineHeight === value}
              onclick={() => (lineHeight = value)}
            >
              {value}
            </button>
          {/each}
        </div>
      </fieldset>

      <fieldset>
        <legend>{$_('reader.margin')}</legend>
        <div class="options">
          {#each MARGINS as value (value)}
            <button
              type="button"
              class:on={margin === value}
              aria-pressed={margin === value}
              onclick={() => (margin = value)}
            >
              {value}
            </button>
          {/each}
        </div>
      </fieldset>
    {/snippet}
  </Dialog>
{/if}

{#if conflict}
  <Dialog title={$_('reader.conflictTitle')} onclose={() => (conflict = null)}>
    {#snippet children()}
      <p data-testid="conflict-body">
        {$_('reader.conflictBody', { values: { page: conflict.page } })}
      </p>
    {/snippet}
    {#snippet footer()}
      <button type="button" data-testid="conflict-stay" onclick={() => (conflict = null)}>
        {$_('reader.conflictStay')}
      </button>
      <button class="primary" type="button" data-testid="conflict-jump" onclick={acceptConflict}>
        {$_('reader.conflictJump')}
      </button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .chrome-toggle {
    position: fixed;
    top: max(var(--space-2), var(--inset-top));
    right: max(var(--space-2), var(--inset-right));
    z-index: 22;
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border: 1px solid var(--line);
    border-radius: var(--radius-pill);
    background: var(--surface-overlay);
    color: var(--text);
    cursor: pointer;
  }
  .position {
    position: fixed;
    top: max(var(--space-2), var(--inset-top));
    left: max(var(--space-2), var(--inset-left));
    z-index: 22;
    margin: 0;
    padding: var(--space-1) var(--space-3);
    border-radius: var(--radius-pill);
    background: var(--surface-overlay);
    color: var(--text-muted);
    font-size: var(--font-xs);
    font-variant-numeric: tabular-nums;
  }
  .topbar,
  .bottombar {
    position: fixed;
    right: 0;
    left: 0;
    z-index: 21;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    background: var(--surface-overlay);
  }
  .topbar {
    top: 0;
    padding-top: max(var(--space-2), calc(var(--inset-top) + var(--space-2)));
    border-bottom: 1px solid var(--line-subtle);
  }
  .bottombar {
    bottom: 0;
    justify-content: space-between;
    padding-bottom: max(var(--space-2), var(--inset-bottom));
    border-top: 1px solid var(--line-subtle);
  }
  .title {
    flex: 1;
    overflow: hidden;
    font-size: var(--font-md);
    font-weight: 600;
    text-align: center;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ic {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border: 0;
    border-radius: var(--radius-sm);
    background: none;
    color: var(--text);
    cursor: pointer;
  }
  .ic:disabled {
    opacity: 0.3;
    cursor: default;
  }
  .pageno {
    color: var(--text-muted);
    font-size: var(--font-sm);
    font-variant-numeric: tabular-nums;
  }
  .nav {
    display: flex;
    align-items: center;
    gap: var(--space-1);
  }
  .page {
    display: flex;
    min-height: 100dvh;
    justify-content: center;
    padding: var(--space-6) var(--epub-margin);
  }
  iframe {
    width: 100%;
    max-width: 46rem;
    min-height: calc(100dvh - var(--space-7));
    border: 0;
    /* The chapter document inherits nothing from this page, so the typography controls
       are applied to the frame box: size through zoom-equivalent width, and the rest by
       the reader's own choice of viewport. A stylesheet cannot be injected without
       running script, which is deliberately not permitted. */
    font-size: var(--epub-font-size);
    line-height: var(--epub-line-height);
    background: var(--surface);
    color-scheme: dark;
  }
  fieldset {
    margin: 0 0 var(--space-3);
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  .options {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .options button {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
    color: var(--text);
    font: inherit;
    font-size: var(--font-sm);
    cursor: pointer;
  }
  .options .on {
    border-color: var(--accent);
    background: var(--accent);
    color: var(--accent-contrast);
    font-weight: 700;
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
</style>
