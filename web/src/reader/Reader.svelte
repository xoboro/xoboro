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
  import { replace } from 'svelte-spa-router'
  import { _ } from '../lib/i18n.js'
  import { pageUrl, readMediaItemReaderContext } from '../lib/api/catalog.js'
  import { writeProgress, resumePage } from '../lib/api/progress.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import { createPriorityLoader } from './priorityLoader.js'
  import { maximumPageWidth } from './imageRequest.js'
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

  let { params, initialContext = null } = $props()

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
  let progressError = $state(null)
  let previousId = $state(null)
  let nextId = $state(null)
  let loadedId = $state('')
  let scrollNode = $state(null)
  let failedViews = $state([])

  const loader = createPriorityLoader()
  let restoring = false
  let leaving = false
  let loadToken = 0
  let openController = null
  let saveTimer = null
  let pendingProgress = null
  let progressWriteToken = 0
  let progressRevision = 0
  let confirmedProgressRevision = 0
  let initialProgressPending = false
  let viewportWidth = $state(window.innerWidth)
  let viewportHeight = $state(window.innerHeight)
  let viewportDensity = $state(window.devicePixelRatio)
  const trackedViews = new Map()
  let trackingFrame = null

  const isScroll = $derived(mode === 'scroll' || mode === 'split-scroll')
  const isSplit = $derived(mode === 'split' || mode === 'split-scroll')
  const views = $derived(pages.length ? buildViews(pages, isSplit, direction) : [])
  // `pageCount`, which is what the media item actually carries. This read was `pagesCount` and so
  // was always `undefined`, silently falling through to the delivered page list's length. The two
  // agree whenever delivery returns every page, which is why nothing showed it — but the analyzed
  // count is the authority for "how long is this", and a short page list is exactly the case where
  // the fallback would report the wrong total and clamp a resume position backwards.
  const pageCount = $derived(item?.media?.pageCount ?? pages.length)

  function flushProgress({ keepalive = false } = {}) {
    clearTimeout(saveTimer)
    saveTimer = null
    if (pendingProgress === null) return
    const progress = pendingProgress
    const mediaItemId = progress.mediaItemId
    const token = ++progressWriteToken
    writeProgress(mediaItemId, {
      page: progress.page,
      completed: progress.completed,
      keepalive,
    })
      .then(() => {
        confirmedProgressRevision = Math.max(confirmedProgressRevision, progress.revision)
        if (
          pendingProgress?.mediaItemId === mediaItemId &&
          pendingProgress.revision <= confirmedProgressRevision
        ) {
          pendingProgress = null
        }
        if (token === progressWriteToken && loadedId === mediaItemId) progressError = null
      })
      .catch((caught) => {
        if (
          token === progressWriteToken &&
          loadedId === mediaItemId &&
          progress.revision > confirmedProgressRevision
        ) {
          progressError = caught
        }
      })
  }

  /**
   * Stops the old route from observing the destination history entry's scroll position.
   * Browsers emit popstate before restoring that entry's document scroll, so the reader
   * otherwise sees the later scroll-to-zero as a genuine return to page one.
  */
  function freezeProgressTracking() {
    if (leaving) return
    leaving = true
    if (trackingFrame !== null) {
      cancelAnimationFrame(trackingFrame)
      trackingFrame = null
    }
    flushProgress()
  }

  function navigate(path) {
    freezeProgressTracking()
    replace(path)
  }

  function noteProgress(page, completed = false) {
    if (leaving) return
    if (isScroll) {
      completed = views.at(-1)?.page === page && isAtDocumentBottom()
    }
    current = page
    if (!loadedId) return
    clearTimeout(saveTimer)
    pendingProgress = {
      mediaItemId: loadedId,
      page,
      completed,
      revision: ++progressRevision,
    }
    saveTimer = setTimeout(flushProgress, PROGRESS_DEBOUNCE_MILLIS)
  }

  async function open(id) {
    const token = ++loadToken
    restoring = true
    leaving = false
    if (trackingFrame !== null) {
      cancelAnimationFrame(trackingFrame)
      trackingFrame = null
    }
    openController?.abort()
    const controller = new AbortController()
    openController = controller
    flushProgress()
    // The flushed write belongs to the item being left. Its eventual result must not
    // set or clear the status for the replacement item.
    progressWriteToken += 1
    loader.reset()
    trackedViews.clear()
    loadedId = ''
    initialProgressPending = false
    pages = []
    failedViews = []
    item = null
    error = null
    progressError = null
    previousId = null
    nextId = null

    try {
      const context =
        initialContext?.item?.id === id
          ? initialContext
          : await readMediaItemReaderContext(id, { signal: controller.signal })
      if (token !== loadToken) return
      const detail = context.item
      const manifest = context.pages ?? []
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
      initialProgressPending = true

      previousId = context.previousId ?? null
      nextId = context.nextId ?? null

      // Two frames: one for Svelte to render the slots, one to scroll to the resumed
      // page before the observer is allowed to report a position.
      requestAnimationFrame(() => {
        if (token !== loadToken) return
        if (isScroll) scrollTo(start)
        requestAnimationFrame(() => {
          if (token === loadToken) {
            restoring = false
            scheduleVisibleTrack()
          }
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
    noteProgress(views[index].page, !isScroll && index === views.length - 1)
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
    const onPageHide = () => flushProgress({ keepalive: true })
    const onPopState = () => freezeProgressTracking()
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') flushProgress({ keepalive: true })
    }
    const onResize = () => {
      const visualViewport = window.visualViewport
      viewportWidth = visualViewport?.width ?? window.innerWidth
      viewportHeight = visualViewport?.height ?? window.innerHeight
      viewportDensity = window.devicePixelRatio
      scheduleVisibleTrack()
    }
    onResize()
    window.addEventListener('keydown', onKeydown)
    window.addEventListener('pagehide', onPageHide)
    window.addEventListener('popstate', onPopState, true)
    window.addEventListener('scroll', noteDocumentScroll, { passive: true })
    window.addEventListener('resize', onResize, { passive: true })
    window.addEventListener('orientationchange', onResize, { passive: true })
    window.visualViewport?.addEventListener('resize', onResize, { passive: true })
    window.visualViewport?.addEventListener('scroll', scheduleVisibleTrack, { passive: true })
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.removeEventListener('keydown', onKeydown)
      window.removeEventListener('pagehide', onPageHide)
      window.removeEventListener('popstate', onPopState, true)
      window.removeEventListener('scroll', noteDocumentScroll)
      window.removeEventListener('resize', onResize)
      window.removeEventListener('orientationchange', onResize)
      window.visualViewport?.removeEventListener('resize', onResize)
      window.visualViewport?.removeEventListener('scroll', scheduleVisibleTrack)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  })

  onDestroy(() => {
    // Flushed rather than dropped: leaving the reader is exactly when the last position
    // matters, and a debounce timer would otherwise be discarded with the component.
    freezeProgressTracking()
    openController?.abort()
    loader.destroy()
    trackedViews.clear()
    if (trackingFrame !== null) cancelAnimationFrame(trackingFrame)
  })

  function failView(view) {
    const key = viewKey(view)
    if (!failedViews.includes(key)) failedViews = [...failedViews, key]
  }

  function retryView(view) {
    const key = viewKey(view)
    failedViews = failedViews.filter((failed) => failed !== key)
  }

  /** Records an opened view once it has pixels, including a final view with no page-turn. */
  function noteInitialViewRendered(view, at) {
    if (!initialProgressPending || !loadedId || at !== index) return
    initialProgressPending = false
    noteProgress(view.page, !isScroll && at === views.length - 1)
  }

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
      const physical = dx < 0 ? 1 : -1
      go(direction === 'rtl' ? -physical : physical)
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

  /**
   * Marks the view crossing the physical centre of the visual viewport.
   *
   * IntersectionObserver percentage root margins are resolved against viewport width,
   * even for the vertical axis. A `-49%` centre strip therefore sits in the wrong place
   * on every non-square phone. Reading the rendered rectangles in one animation frame
   * keeps the calculation tied to the actual viewport height and coalesces scroll work.
   */
  function scheduleVisibleTrack() {
    if (leaving || trackingFrame !== null) return
    trackingFrame = requestAnimationFrame(() => {
      trackingFrame = null
      if (leaving || restoring || !isScroll || trackedViews.size === 0) return
      // The physical end wins over the centre. A short final slot can leave the
      // viewport centre inside the preceding page even though the reader reached bottom.
      if (isAtDocumentBottom()) return
      const visualViewport = window.visualViewport
      const centre =
        (visualViewport?.offsetTop ?? 0) + (visualViewport?.height ?? viewportHeight) / 2
      let chosen = null
      let chosenContainsCentre = false
      let chosenDistance = Number.POSITIVE_INFINITY
      for (const [node, at] of trackedViews) {
        const rect = node.getBoundingClientRect()
        if (!Number.isFinite(rect.top) || !Number.isFinite(rect.bottom) || rect.bottom <= rect.top) {
          continue
        }
        const containsCentre = centre >= rect.top && centre < rect.bottom
        const distance = containsCentre
          ? 0
          : Math.min(Math.abs(centre - rect.top), Math.abs(centre - rect.bottom))
        if (
          chosen === null ||
          (containsCentre && !chosenContainsCentre) ||
          (containsCentre === chosenContainsCentre && distance < chosenDistance)
        ) {
          chosen = at
          chosenContainsCentre = containsCentre
          chosenDistance = distance
        }
      }
      if (chosen === null || chosen.index === index) return
      index = chosen.index
      noteProgress(chosen.page, false)
    })
  }

  /** Registers a rendered slot with the single viewport-centre tracker. */
  function track(node, position) {
    let at = position
    trackedViews.set(node, at)
    scheduleVisibleTrack()
    return {
      update(next) {
        at = next
        trackedViews.set(node, at)
        scheduleVisibleTrack()
      },
      destroy() {
        trackedViews.delete(node)
      },
    }
  }

  function isAtDocumentBottom() {
    const node = document.scrollingElement ?? document.documentElement
    return node.scrollTop + node.clientHeight >= node.scrollHeight - 1
  }

  function noteDocumentScroll() {
    if (leaving || restoring || !isScroll || views.length === 0) return
    scheduleVisibleTrack()
    if (!isAtDocumentBottom()) return
    index = views.length - 1
    noteProgress(views[index].page)
  }

  function displayWidth(view) {
    const viewport = viewportWidth
    const preferred = Number(width)
    const available =
      isScroll && width !== 'full' && Number.isFinite(preferred)
        ? Math.min(viewport, preferred)
        : viewport
    if ((isScroll && fit !== 'height') || !view.width || !view.height) return available
    return Math.min(available, viewportHeight * (view.width / view.height))
  }

  function slotStyle(view) {
    const declarations = []
    if ((!isScroll || fit === 'height') && view.width && view.height) {
      declarations.push(`width:${displayWidth(view)}px`)
    }
    const ratio = aspectRatio(view)
    if (ratio) declarations.push(`aspect-ratio:${ratio}`)
    return declarations.join(';')
  }

  function imageStyle(view) {
    return view.half === null ? 'width:100%' : 'width:200%;max-width:none'
  }

  function imageUrl(view) {
    const maxWidth = maximumPageWidth({
      sourceWidth: view.half === null ? view.width : view.width * 2,
      displayWidth: displayWidth(view),
      devicePixelRatio: viewportDensity,
      split: view.half !== null,
    })
    return pageUrl(loadedId, view.page, { maxWidth })
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
    <a
      class="ic"
      href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'}
      aria-label={$_('common.back')}
      onclick={(event) => {
        event.preventDefault()
        navigate(item?.seriesId ? `/series/${item.seriesId}` : '/')
      }}
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
    <span class="pageno">{current} / {pageCount}</span>
    <div class="nav">
      <button
        class="ic"
        type="button"
        data-testid="previous-item"
        disabled={!previousId}
        aria-label={$_('reader.previousItem')}
        onclick={() => previousId && navigate(`/read/${previousId}`)}
      >
        <ChevronLeft size={22} aria-hidden="true" />
      </button>
      <a
        class="ic"
        href={item?.seriesId ? `#/series/${item.seriesId}` : '#/'}
        aria-label={$_('common.list')}
        onclick={(event) => {
          event.preventDefault()
          navigate(item?.seriesId ? `/series/${item.seriesId}` : '/')
        }}
      >
        <List size={20} aria-hidden="true" />
      </a>
      <button
        class="ic"
        type="button"
        data-testid="next-item"
        disabled={!nextId}
        aria-label={$_('reader.nextItem')}
        onclick={() => nextId && navigate(`/read/${nextId}`)}
      >
        <ChevronRight size={22} aria-hidden="true" />
      </button>
    </div>
  </div>
{/if}

<ErrorNotice {error} />

{#if progressError}
  <div class="progress-error" style="position: fixed" data-testid="progress-error">
    <ErrorNotice error={progressError} />
  </div>
{/if}

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
          style={slotStyle(view)}
          use:track={{ page: view.page, index: at }}
        >
          {#if failedViews.includes(viewKey(view))}
            <button
              class="slot-retry"
              type="button"
              aria-label={`${$_('common.retry')} ${view.page}`}
              data-testid={`retry-page-${viewKey(view)}`}
              onclick={() => retryView(view)}
            >
              {$_('common.retry')}
            </button>
          {:else}
            <img
              use:loader.load={{
                url: imageUrl(view),
                priority: pageLoadPriority(view.page, current, pageCount),
                onSuccess: () => noteInitialViewRendered(view, at),
                onFailure: () => failView(view),
              }}
              alt=""
              role="presentation"
              decoding="async"
              style={imageStyle(view)}
            />
          {/if}
        </div>
      {/each}
    </div>
  {:else}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="stage" onpointerdown={pointerDown} onpointerup={pointerUp}>
      {#if views[index]}
        {@const view = views[index]}
        <div
          class="slot paged"
          class:half={view.half !== null}
          class:right={view.half === 'R'}
          style={slotStyle(view)}
        >
          {#if failedViews.includes(viewKey(view))}
            <button
              class="slot-retry"
              type="button"
              aria-label={`${$_('common.retry')} ${view.page}`}
              data-testid={`retry-page-${viewKey(view)}`}
              onclick={() => retryView(view)}
            >
              {$_('common.retry')}
            </button>
          {:else}
            <img
              use:loader.load={{
                url: imageUrl(view),
                priority: 0,
                onSuccess: () => noteInitialViewRendered(view, index),
                onFailure: () => failView(view),
              }}
              alt=""
              role="presentation"
              decoding="async"
              style={imageStyle(view)}
            />
          {/if}
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
    padding-right: max(var(--space-3), calc(var(--inset-right) + var(--space-2)));
    padding-left: max(var(--space-3), calc(var(--inset-left) + var(--space-2)));
    background: var(--surface-overlay);
  }
  .progress-error {
    position: fixed;
    right: max(var(--space-3), var(--inset-right));
    bottom: max(var(--space-3), var(--inset-bottom));
    left: max(var(--space-3), var(--inset-left));
    z-index: 22;
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
  .scroll {
    min-height: 100dvh;
  }
  .slot {
    position: relative;
    display: block;
    width: min(100%, var(--reader-width, 100%));
    margin-inline: auto;
    overflow: hidden;
  }
  .slot img {
    display: block;
    width: 100%;
  }
  .slot-retry {
    position: absolute;
    top: 50%;
    left: 50%;
    min-width: var(--touch-target);
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    transform: translate(-50%, -50%);
    border: 1px solid var(--danger);
    border-radius: var(--radius-sm);
    background: var(--surface-overlay);
    color: var(--text);
    font: inherit;
    cursor: pointer;
  }
  /* A spread is shown at double width and slid sideways, so each half fills the slot
     without the server having to cut the image. */
  .slot.half img {
    width: 200%;
  }
  .slot.half.right img {
    transform: translateX(-50%);
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
  .slot.paged.half img {
    max-width: none;
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
