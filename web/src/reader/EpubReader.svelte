<script>
  import { onDestroy, onMount, untrack } from 'svelte'
  import { ChevronLeft, ChevronRight, List, Settings } from '@lucide/svelte'
  import { replace } from 'svelte-spa-router'
  import { _ } from '../lib/i18n.js'
  import {
    isRetryableDeliveryFailure,
    readMediaItemReaderContext,
    resourceUrlFor,
  } from '../lib/api/catalog.js'
  import { writeProgress } from '../lib/api/progress.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import { bindEpubFrame } from './epubFrame.js'
  import { epubProgressFor, epubResume } from './epubPosition.js'

  let { params, initialContext = null } = $props()

  const PROGRESS_DEBOUNCE_MILLIS = 800
  const FONT_SIZES = ['90', '100', '115', '130']
  const LINE_HEIGHTS = ['1.4', '1.6', '1.8']
  const WIDTHS = ['34', '42', '52', 'full']
  const MARGINS = ['16', '32', '64']
  const THEMES = ['dark', 'light']

  const stored = (key, values, fallback) => {
    try {
      const value = localStorage.getItem(`xoboro.epub.${key}`)
      return values.includes(value) ? value : fallback
    } catch {
      return fallback
    }
  }

  const remember = (key, value) => {
    try {
      localStorage.setItem(`xoboro.epub.${key}`, value)
    } catch {
      // A refused preference write must not interrupt reading.
    }
  }

  let fontSize = $state(stored('font-size', FONT_SIZES, '100'))
  let lineHeight = $state(stored('line-height', LINE_HEIGHTS, '1.6'))
  let columnWidth = $state(stored('width', WIDTHS, '42'))
  let margin = $state(stored('margin', MARGINS, '32'))
  let theme = $state(stored('theme', THEMES, 'dark'))

  $effect(() => remember('font-size', fontSize))
  $effect(() => remember('line-height', lineHeight))
  $effect(() => remember('width', columnWidth))
  $effect(() => remember('margin', margin))
  $effect(() => remember('theme', theme))

  let item = $state(null)
  let positions = $state([])
  let at = $state(0)
  let chrome = $state(false)
  let settingsOpen = $state(false)
  let error = $state(null)
  let progressError = $state(null)
  let retryable = $state(false)
  let previousId = $state(null)
  let nextId = $state(null)
  let loadedId = $state('')
  let restoreProgression = $state(0)
  let restoreKey = $state(0)

  let saveTimer = null
  let pendingProgress = null
  let loadController = null
  let loadToken = 0
  let progressWriteToken = 0

  const position = $derived(positions[at] ?? null)
  const total = $derived(positions.length)
  const pageCount = $derived(item?.media?.pageCount ?? 1)
  const source = $derived(
    loadedId && position ? resourceUrlFor(loadedId, position.href) : null,
  )
  const frameOptions = $derived({
    restoreKey,
    progression: restoreProgression,
    styles: { fontSize, lineHeight, margin, width: columnWidth, theme },
    onProgress: noteFrameProgress,
    onToggleChrome: toggleChrome,
    onKeydown,
  })

  function flushProgress({ keepalive = false } = {}) {
    clearTimeout(saveTimer)
    saveTimer = null
    if (!pendingProgress) return
    const write = pendingProgress
    pendingProgress = null
    const token = ++progressWriteToken
    writeProgress(write.id, { ...write.progress, keepalive })
      .then(() => {
        if (token === progressWriteToken && loadedId === write.id) progressError = null
      })
      .catch((caught) => {
        if (token === progressWriteToken && loadedId === write.id) progressError = caught
      })
  }

  function noteProgress(progress) {
    if (!loadedId) return
    clearTimeout(saveTimer)
    pendingProgress = { id: loadedId, progress }
    saveTimer = setTimeout(flushProgress, PROGRESS_DEBOUNCE_MILLIS)
  }

  function noteFrameProgress({ progression, atBottom }) {
    if (!position) return
    const progress = epubProgressFor(positions, position.href, progression, pageCount, atBottom)
    at = progress.index
    noteProgress(progress)
  }

  async function load(id) {
    const token = ++loadToken
    loadController?.abort()
    const controller = new AbortController()
    loadController = controller
    flushProgress()
    progressWriteToken += 1
    item = null
    positions = []
    previousId = null
    nextId = null
    loadedId = ''
    error = null
    progressError = null
    retryable = false

    try {
      const context =
        initialContext?.item?.id === id
          ? initialContext
          : await readMediaItemReaderContext(id, { signal: controller.signal })
      if (token !== loadToken || controller.signal.aborted) return
      const detail = context.item
      const order = context.positions ?? []
      const resume = epubResume(detail.progress, order, detail.media?.pageCount)
      item = detail
      positions = order
      previousId = context.previousId ?? null
      nextId = context.nextId ?? null
      at = resume.index
      restoreProgression = resume.progression
      restoreKey += 1
      loadedId = id
    } catch (caught) {
      if (token !== loadToken || controller.signal.aborted) return
      error = caught
      retryable = isRetryableDeliveryFailure(caught)
    } finally {
      if (loadController === controller) loadController = null
    }
  }

  $effect(() => {
    const id = params.id
    untrack(() => load(id))
  })

  function go(delta) {
    const next = at + delta
    if (next < 0) {
      if (previousId) replace(`/read/${previousId}`)
      return
    }
    if (next >= positions.length) {
      if (nextId) replace(`/read/${nextId}`)
      return
    }
    at = next
    restoreProgression = Number(positions[next].progression) || 0
    restoreKey += 1
    noteProgress(
      epubProgressFor(positions, positions[next].href, restoreProgression, pageCount, false),
    )
  }

  function toggleChrome() {
    chrome = !chrome
    if (!chrome) settingsOpen = false
  }

  function fromChrome(event) {
    if (event.altKey || event.ctrlKey || event.metaKey) return true
    return Boolean(event.target?.closest?.('input, textarea, select, button, a, [role="dialog"]'))
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

  function leaveFor(event, path) {
    event.preventDefault()
    replace(path)
  }

  onMount(() => {
    const onPageHide = () => flushProgress({ keepalive: true })
    window.addEventListener('keydown', onKeydown)
    window.addEventListener('pagehide', onPageHide)
    return () => {
      window.removeEventListener('keydown', onKeydown)
      window.removeEventListener('pagehide', onPageHide)
    }
  })

  onDestroy(() => {
    flushProgress()
    progressWriteToken += 1
    loadController?.abort()
  })
</script>

<button
  class="visually-hidden"
  type="button"
  data-testid="keyboard-chrome-toggle"
  aria-expanded={chrome}
  aria-label={$_('reader.controls')}
  onclick={toggleChrome}
>
  {$_('reader.controls')}
</button>

{#if total > 0}
  <p class="visually-hidden" role="status" aria-live="polite" data-testid="position">
    {$_('reader.position', { values: { page: at + 1, total } })}
  </p>
{/if}

{#if chrome}
  <div class="topbar">
    <a
      class="ic"
      href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'}
      aria-label={$_('common.back')}
      onclick={(event) => leaveFor(event, item?.seriesId ? `/series/${item.seriesId}` : '/')}
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
        disabled={at === 0 && !previousId}
        aria-label={$_('reader.previousItem')}
        onclick={() => go(-1)}
      >
        <ChevronLeft size={22} aria-hidden="true" />
      </button>
      <a
        class="ic"
        href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'}
        aria-label={$_('common.list')}
        onclick={(event) => leaveFor(event, item?.seriesId ? `/series/${item.seriesId}` : '/')}
      >
        <List size={20} aria-hidden="true" />
      </a>
      <button
        class="ic"
        type="button"
        data-testid="next-position"
        disabled={at >= total - 1 && !nextId}
        aria-label={$_('reader.nextItem')}
        onclick={() => go(1)}
      >
        <ChevronRight size={22} aria-hidden="true" />
      </button>
    </div>
  </div>
{/if}

<ErrorNotice {error} onretry={retryable ? () => load(params.id) : null} />
{#if progressError}
  <div class="progress-error" style="position: fixed">
    <ErrorNotice error={progressError} />
  </div>
{/if}

{#if source}
  <div class="page">
    {#key source}
      <iframe
        src={source}
        title={item?.title ?? $_('reader.chapter')}
        data-testid="chapter-frame"
        sandbox="allow-same-origin"
        referrerpolicy="no-referrer"
        use:bindEpubFrame={frameOptions}
      ></iframe>
    {/key}
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
              data-testid={`line-height-${value}`}
              aria-pressed={lineHeight === value}
              onclick={() => (lineHeight = value)}
            >
              {value}
            </button>
          {/each}
        </div>
      </fieldset>

      <fieldset>
        <legend>{$_('reader.displayWidth')}</legend>
        <div class="options">
          {#each WIDTHS as value (value)}
            <button
              type="button"
              class:on={columnWidth === value}
              data-testid={`width-${value}`}
              aria-pressed={columnWidth === value}
              onclick={() => (columnWidth = value)}
            >
              {$_(`reader.epubWidths.${value}`)}
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

      <fieldset>
        <legend>{$_('reader.theme')}</legend>
        <div class="options">
          {#each THEMES as value (value)}
            <button
              type="button"
              class:on={theme === value}
              data-testid={`theme-${value}`}
              aria-pressed={theme === value}
              onclick={() => (theme = value)}
            >
              {$_(`reader.themes.${value}`)}
            </button>
          {/each}
        </div>
      </fieldset>
    {/snippet}
  </Dialog>
{/if}

<style>
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
  }
  iframe {
    width: 100%;
    min-height: 100dvh;
    border: 0;
    background: var(--surface);
  }
  .progress-error {
    right: max(var(--space-3), var(--inset-right));
    bottom: calc(var(--touch-target) + max(var(--space-3), var(--inset-bottom)));
    z-index: 24;
    max-width: min(28rem, calc(100vw - var(--space-6)));
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
</style>
