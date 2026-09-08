<script>
  /**
   * The comic reader.
   *
   * Ported from the base UI, keeping the three things it got right — the single-flight
   * priority loader, reserving each slot's aspect ratio before its image loads, and
   * direction-aware spread splitting — and fixing the accessibility it got wrong.
   *
   * What changed, and why each was a real defect rather than a nicety:
   *
   * - The chrome keeps the source reader's page-tap interaction. Its keyboard path is
   *   visually hidden so it does not put a permanent control over the comic.
   * - The settings panel was a plain fixed `div` with no role, no `aria-modal` and no
   *   Escape, while `FilterSheet` in the same codebase did all of it. It now uses the
   *   one shared Dialog.
   * - Page images carried `alt="p12"` — a position, not a description, and noise for a
   *   screen reader. They are decorative now, and the position is announced once as
   *   live text where it is actually useful.
   * - Progress write failures surface without interrupting reading for a stale write
   *   the server has already resolved safely.
   */
  import { onDestroy, onMount, untrack } from 'svelte'
  import { ChevronLeft, ChevronRight, List, Settings } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { listPages, pageUrl, readMediaItem, readNeighbour } from '../lib/api/catalog.js'
  import { writeProgress, resumePage } from '../lib/api/progress.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import { createPriorityLoader } from './priorityLoader.js'
  import {
    DIRECTIONS,
    aspectRatio,
    buildViews,
    indexOfPage,
    pageLoadPriority,
    viewKey,
  } from './views.js'
  import { Preference, oneOf, readPreference, writePreference } from '../lib/preferences.js'
  import { session } from '../lib/session.js'

  let { params, initialItem = null } = $props()

  const PROGRESS_DEBOUNCE_MILLIS = 800
  const MODES = ['scroll', 'paged', 'split', 'split-scroll']
  const WIDTHS = ['600', '760', '1000', 'full']

  const stored = (key, fallback) => {
    try {
      return localStorage.getItem(`xoboro.reader.${key}`) ?? fallback
    } catch {
      return fallback
    }
  }
  const remember = (key, value) => {
    try {
      localStorage.setItem(`xoboro.reader.${key}`, value)
    } catch {
      // A refused write costs the preference, not the reading session.
    }
  }

  /**
   * Named combinations of layout and direction.
   *
   * `mode` and `direction` are separate axes, which is correct — a right-to-left
   * webtoon exists — but it left the common cases undiscoverable: reading Japanese
   * manga means knowing to pick `paged` *and* `rtl`, and nothing on the sheet said so.
   * These name the three combinations a reader actually asks for; the axes stay below
   * for anything else.
   */
  const PRESETS = Object.freeze([
    { key: 'webtoon', mode: 'scroll', direction: 'ltr' },
    { key: 'manga', mode: 'paged', direction: 'rtl' },
    { key: 'comic', mode: 'paged', direction: 'ltr' },
  ])

  /**
   * The series this item belongs to, once known.
   *
   * Layout and direction are remembered against it rather than globally: a shelf
   * holding both Japanese manga and a webtoon wants opposite answers, and one global
   * setting is wrong for one of them on every open.
   */
  let seriesId = $state(null)
  const readerId = $derived($session.user?.id ?? null)

  // Seeded from the series-less scope, which readPreference resolves to the legacy
  // global value. That keeps a reader's existing choice until the item loads and its
  // series has a say, instead of flashing the shipped default.
  let mode = $state(oneOf(readPreference(null, null, Preference.MODE, 'scroll'), MODES, 'scroll'))
  let direction = $state(
    oneOf(readPreference(null, null, Preference.DIRECTION, 'ltr'), DIRECTIONS, 'ltr'),
  )
  let fit = $state(stored('fit', 'width'))
  let width = $state(stored('width', '760'))

  // Only fit and width are global. They describe the screen being read on, which does
  // not change from one work to the next.
  $effect(() => remember('fit', fit))
  $effect(() => remember('width', width))

  const activePreset = $derived(
    PRESETS.find((preset) => preset.mode === mode && preset.direction === direction)?.key ?? null,
  )

  /** Applies what this reader last chose for this series, if anything. */
  function restoreViewPreferences() {
    mode = oneOf(readPreference(readerId, seriesId, Preference.MODE, mode), MODES, mode)
    direction = oneOf(
      readPreference(readerId, seriesId, Preference.DIRECTION, direction),
      DIRECTIONS,
      direction,
    )
  }

  /**
   * Records a view choice against the current series.
   *
   * Written on the action rather than from an effect on `mode`: an effect also fires
   * for the programmatic restore above, which would store the value under whichever
   * scope happened to be current at the time.
   */
  function chooseView(next) {
    if (next.mode !== undefined) {
      mode = next.mode
      writePreference(readerId, seriesId, Preference.MODE, mode)
    }
    if (next.direction !== undefined) {
      direction = next.direction
      writePreference(readerId, seriesId, Preference.DIRECTION, direction)
    }
    realign()
  }

  let item = $state(null)
  let pages = $state([])
  let current = $state(1)
  let index = $state(0)
  let chrome = $state(false)
  let settingsOpen = $state(false)
  let error = $state(null)
  let previousId = $state(null)
  let nextId = $state(null)
  let loadedId = $state('')
  let scrollNode = $state(null)

  const loader = createPriorityLoader()
  let restoring = false
  let loadToken = 0
  let openController = null
  let saveTimer = null
  let pendingPage = null

  const isScroll = $derived(mode === 'scroll' || mode === 'split-scroll')
  const isSplit = $derived(mode === 'split' || mode === 'split-scroll')
  const views = $derived(pages.length ? buildViews(pages, isSplit, direction) : [])
  // `pageCount`, which is what the media item actually carries. This read was `pagesCount` and so
  // was always `undefined`, silently falling through to the delivered page list's length. The two
  // agree whenever delivery returns every page, which is why nothing showed it — but the analyzed
  // count is the authority for "how long is this", and a short page list is exactly the case where
  // the fallback would report the wrong total and clamp a resume position backwards.
  const pageCount = $derived(item?.media?.pageCount ?? pages.length)

  function flushProgress() {
    clearTimeout(saveTimer)
    saveTimer = null
    if (pendingPage === null || !loadedId) return
    const page = pendingPage
    pendingPage = null
    writeProgress(loadedId, { page }).catch((caught) => (error = caught))
  }

  function noteProgress(page) {
    current = page
    if (!loadedId) return
    clearTimeout(saveTimer)
    pendingPage = page
    saveTimer = setTimeout(flushProgress, PROGRESS_DEBOUNCE_MILLIS)
  }

  async function open(id) {
    const token = ++loadToken
    openController?.abort()
    const controller = new AbortController()
    openController = controller
    flushProgress()
    loader.reset()
    loadedId = ''
    pages = []
    item = null
    previousId = null
    nextId = null
    restoring = true

    try {
      const detailRequest =
        initialItem?.id === id
          ? Promise.resolve(initialItem)
          : readMediaItem(id, { signal: controller.signal })
      const [detail, manifest] = await Promise.all([
        detailRequest,
        listPages(id, { signal: controller.signal }),
      ])
      if (token !== loadToken) return
      item = detail
      // Restored before the first view is built: `index` below is derived from
      // `direction`, so applying the series' direction afterwards would open a split
      // spread on the wrong half and then jump.
      seriesId = detail.seriesId ?? null
      restoreViewPreferences()
      pages = manifest
      // `progress`, which is what the media-item route sends. This read was `readProgress` —
      // the name the *domain* type uses on the server, not the one on the wire — so it was
      // always undefined and `resumePage` fell through to page one. Every reader opened at the
      // beginning no matter where they stopped, and the fixtures said `readProgress` too, so
      // the suite agreed with the component and neither agreed with the server.
      const start = resumePage(detail.progress, detail.media?.pageCount ?? manifest.length)
      current = start
      loadedId = id
      index = indexOfPage(buildViews(manifest, isSplit, direction), start)

      // Started before the DOM settles, because they do not depend on it. They were
      // originally sequenced after an `await tick()` and never ran at all — a tick
      // awaited from inside an effect's own async continuation does not resolve here,
      // and everything after it was silently skipped, so previous/next stayed disabled
      // for every item.
      readNeighbour(id, 'previous', { signal: controller.signal })
        .then((found) => {
          if (token === loadToken) previousId = found?.id ?? null
        })
        .catch(() => {})
      readNeighbour(id, 'next', { signal: controller.signal })
        .then((found) => {
          if (token === loadToken) nextId = found?.id ?? null
        })
        .catch(() => {})

      // Two frames: one for Svelte to render the slots, one to scroll to the resumed
      // page before the observer is allowed to report a position.
      requestAnimationFrame(() => {
        if (token !== loadToken) return
        if (isScroll) scrollTo(start)
        requestAnimationFrame(() => {
          if (token === loadToken) restoring = false
        })
      })
    } catch (caught) {
      if (token === loadToken && caught?.name !== 'AbortError') {
        error = caught
        restoring = false
      }
    } finally {
      if (openController === controller) openController = null
    }
  }

  $effect(() => {
    const id = params.id
    // Only the identifier is tracked. `open` reads the layout state to work out the
    // starting view, and without untrack the effect would depend on it too — so
    // switching from scroll to paged would re-fetch the item, re-resolve progress and
    // throw away the reader's position.
    untrack(() => open(id))
  })

  function scrollTo(page) {
    scrollNode?.querySelector(`[data-page="${page}"]`)?.scrollIntoView({ block: 'start' })
  }

  function go(delta) {
    const next = index + delta
    if (next < 0 || next >= views.length) return
    index = next
    noteProgress(views[index].page)
    if (isScroll) {
      const at = index
      requestAnimationFrame(() => {
        scrollNode?.querySelector(`[data-view="${at}"]`)?.scrollIntoView({ block: 'start' })
      })
    }
  }

  function realign() {
    index = indexOfPage(views, current)
    if (isScroll) {
      const page = current
      requestAnimationFrame(() => scrollTo(page))
    }
  }

  function toggleChrome() {
    chrome = !chrome
    if (!chrome) settingsOpen = false
  }

  /** Ignores keys aimed at a control, so typing in a field does not page the reader. */
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
    const physical = event.key === 'ArrowRight' ? 1 : -1
    // A right-to-left comic advances when the reader presses left.
    go(direction === 'rtl' ? -physical : physical)
  }

  onMount(() => {
    window.addEventListener('keydown', onKeydown)
    return () => window.removeEventListener('keydown', onKeydown)
  })

  onDestroy(() => {
    // Flushed rather than dropped: leaving the reader is exactly when the last position
    // matters, and a debounce timer would otherwise be discarded with the component.
    flushProgress()
    openController?.abort()
    loader.reset()
  })

  const TAP_SLOP = 10
  const SWIPE_DISTANCE = 40

  let sx = 0
  let sy = 0
  function pointerDown(event) {
    sx = event.clientX
    sy = event.clientY
  }

  /** A press that neither swiped nor dragged, so it was meant as a tap. */
  function isTap(event) {
    return (
      Math.abs(event.clientX - sx) < TAP_SLOP && Math.abs(event.clientY - sy) < TAP_SLOP
    )
  }

  function pointerUp(event) {
    const dx = event.clientX - sx
    const dy = event.clientY - sy
    if (Math.abs(dx) > SWIPE_DISTANCE && Math.abs(dx) > Math.abs(dy)) {
      go(dx < 0 ? 1 : -1)
      return
    }
    if (isTap(event)) {
      const third = window.innerWidth / 3
      if (event.clientX < third) go(direction === 'rtl' ? 1 : -1)
      else if (event.clientX > third * 2) go(direction === 'rtl' ? -1 : 1)
      else toggleChrome()
    }
  }

  /**
   * Tapping the page while scrolling.
   *
   * The handlers above were on the paged stage only, so in the scrolling modes — one of
   * which is the shipped default — tapping the page did nothing and the bar could only
   * be reached through the small button in the corner.
   *
   * A tap anywhere toggles, rather than the paged stage's three zones: scrolling is
   * already the navigation here, so an edge tap is a reader reaching for the bar and not
   * asking for the next page. The drag threshold is what keeps a flick to scroll from
   * counting as one.
   */
  function scrollPointerUp(event) {
    if (isTap(event)) toggleChrome()
  }

  /** Marks the visible view while scrolling, so progress follows the reader. */
  function track(node, position) {
    let at = position
    const observer = new IntersectionObserver(
      (entries) =>
        entries.forEach((entry) => {
          if (!restoring && entry.isIntersecting) {
            index = at.index
            noteProgress(at.page)
          }
        }),
      { threshold: 0.5 },
    )
    observer.observe(node)
    return {
      update(next) {
        at = next
      },
      destroy: () => observer.disconnect(),
    }
  }
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

<!-- The position, announced once where it is useful, instead of repeated in every
     image's alt text. Held back until the page count is known: "page 1 of 0" is not a
     position, and announcing it would say so out loud. -->
{#if pageCount > 0}
  <p class="visually-hidden" role="status" aria-live="polite" data-testid="position">
    {$_('reader.position', { values: { page: current, total: pageCount } })}
  </p>
{/if}

{#if chrome}
  <div class="topbar">
    <a class="ic" href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'} aria-label={$_('common.back')}>
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
    <span class="pageno">{current} / {pageCount}</span>
    <div class="nav">
      <button
        class="ic"
        type="button"
        data-testid="previous-item"
        disabled={!previousId}
        aria-label={$_('reader.previousItem')}
        onclick={() => previousId && (window.location.hash = `#/read/${previousId}`)}
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
        data-testid="next-item"
        disabled={!nextId}
        aria-label={$_('reader.nextItem')}
        onclick={() => nextId && (window.location.hash = `#/read/${nextId}`)}
      >
        <ChevronRight size={22} aria-hidden="true" />
      </button>
    </div>
  </div>
{/if}

<ErrorNotice {error} />

{#key loadedId}
  {#if isScroll}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div
      class="scroll"
      class:fit-height={fit === 'height'}
      style={`--reader-width:${width === 'full' ? '100%' : `${width}px`}`}
      bind:this={scrollNode}
      onpointerdown={pointerDown}
      onpointerup={scrollPointerUp}
    >
      {#each views as view, at (viewKey(view))}
        <div
          class="slot"
          class:half={view.half !== null}
          class:right={view.half === 'R'}
          data-page={view.page}
          data-view={at}
          style={aspectRatio(view) ? `aspect-ratio:${aspectRatio(view)}` : ''}
        >
          <img
            use:loader.load={{
              url: pageUrl(loadedId, view.page),
              priority: pageLoadPriority(view.page, current, pageCount),
            }}
            use:track={{ page: view.page, index: at }}
            alt=""
            role="presentation"
            decoding="async"
          />
        </div>
      {/each}
    </div>
  {:else}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="stage" onpointerdown={pointerDown} onpointerup={pointerUp}>
      {#if views[index]}
        {@const view = views[index]}
        <div class="slot paged" class:half={view.half !== null} class:right={view.half === 'R'}>
          <img src={pageUrl(loadedId, view.page)} alt="" role="presentation" decoding="async" />
        </div>
      {/if}
    </div>
  {/if}
{/key}

{#if settingsOpen}
  <Dialog title={$_('common.settings')} onclose={() => (settingsOpen = false)}>
    {#snippet children()}
      <!-- First on the sheet, because it is the only control most readers need: the
           two fieldsets under it are the same settings taken apart. A preset reads as
           unselected once either axis is changed on its own, which is honest — the
           layout no longer is that preset. -->
      <fieldset>
        <legend>{$_('reader.preset')}</legend>
        <div class="options">
          {#each PRESETS as preset (preset.key)}
            <button
              type="button"
              class:on={activePreset === preset.key}
              data-testid={`preset-${preset.key}`}
              aria-pressed={activePreset === preset.key}
              onclick={() => chooseView({ mode: preset.mode, direction: preset.direction })}
            >
              {$_(`reader.presets.${preset.key}`)}
            </button>
          {/each}
        </div>
      </fieldset>

      <fieldset>
        <legend>{$_('reader.mode')}</legend>
        <div class="options">
          {#each MODES as value (value)}
            <button
              type="button"
              class:on={mode === value}
              data-testid={`mode-${value}`}
              aria-pressed={mode === value}
              onclick={() => chooseView({ mode: value })}
            >
              {$_(`reader.modes.${value}`)}
            </button>
          {/each}
        </div>
      </fieldset>

      <fieldset>
        <legend>{$_('reader.direction')}</legend>
        <div class="options">
          {#each ['ltr', 'rtl'] as value (value)}
            <button
              type="button"
              class:on={direction === value}
              data-testid={`direction-${value}`}
              aria-pressed={direction === value}
              onclick={() => chooseView({ direction: value })}
            >
              {$_(`reader.directions.${value}`)}
            </button>
          {/each}
        </div>
      </fieldset>

      <fieldset>
        <legend>{$_('reader.fit')}</legend>
        <div class="options">
          {#each ['width', 'height'] as value (value)}
            <button
              type="button"
              class:on={fit === value}
              aria-pressed={fit === value}
              onclick={() => (fit = value)}
            >
              {$_(`reader.fits.${value}`)}
            </button>
          {/each}
        </div>
      </fieldset>

      {#if isScroll && fit === 'width'}
        <fieldset>
          <legend>{$_('reader.displayWidth')}</legend>
          <div class="options">
            {#each WIDTHS as value (value)}
              <button
                type="button"
                class:on={width === value}
                aria-pressed={width === value}
                onclick={() => (width = value)}
              >
                {$_(`reader.widths.${value}`)}
              </button>
            {/each}
          </div>
        </fieldset>
      {/if}
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
    padding-right: max(var(--space-3), var(--inset-right));
    padding-left: max(var(--space-3), var(--inset-left));
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
  .scroll {
    min-height: 100dvh;
  }
  .slot {
    display: block;
    width: min(100%, var(--reader-width, 100%));
    margin-inline: auto;
    overflow: hidden;
  }
  .slot img {
    display: block;
    width: 100%;
  }
  /* A spread is shown at double width and slid sideways, so each half fills the slot
     without the server having to cut the image. */
  .slot.half img {
    width: 200%;
  }
  .slot.half.right img {
    transform: translateX(-50%);
  }
  .fit-height .slot img {
    width: auto;
    max-width: 100%;
    height: 100dvh;
    margin-inline: auto;
  }
  .stage {
    display: flex;
    min-height: 100dvh;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    touch-action: pan-y;
  }
  .slot.paged {
    width: 100%;
  }
  .slot.paged img {
    max-width: 100%;
    max-height: 100dvh;
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
