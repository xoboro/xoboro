<script>
  /**
   * The reader's home.
   *
   * Shelves come from the **named feeds**, so what "latest" means is the server's
   * answer rather than this client's. Nothing here passes `sort` to a feed: the route
   * rejects it with `400 invalid_query` rather than ignoring it, so a sort would be a
   * defect and not a preference.
   *
   * Refresh is driven by events, not by a poll ladder. The base this UI grew from
   * re-asked on a hand-tuned backoff — `[5s, 10s, 20s, 30s, 60s, 60s, 60s]` — because
   * Komga gave it nothing better; the measured runtime queues over ten thousand tasks
   * on a 15,000-item catalog, so "ask again in five seconds and see if the numbers
   * moved" is both expensive and unrelated to when the work actually lands.
   */
  import { onMount } from 'svelte'
  import {
    Languages,
    Layers3,
    BookOpen,
    LogOut,
    Search,
    Settings,
    SlidersHorizontal,
  } from '@lucide/svelte'
  import { _, applyLocale, locale } from '../lib/i18n.js'
  import { isAdministrator, session, signOut } from '../lib/session.js'
  import { artworkUrl, listSeries, readFeed } from '../lib/api/catalog.js'
  import { searchCatalog } from '../lib/api/catalogSearch.js'
  import { listLibraries } from '../lib/api/libraries.js'
  import { eventHub } from '../lib/eventHub.js'
  import { Preference, readPreference, writePreference } from '../lib/preferences.js'
  import LibrarySwitcher from './LibrarySwitcher.svelte'
  import Cover from '../components/Cover.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Logo from '../components/Logo.svelte'
  import MediaShelf from '../components/MediaShelf.svelte'
  import Pager from '../components/Pager.svelte'
  import StreamIndicator from '../components/StreamIndicator.svelte'

  const streamStore = eventHub.status

  /**
   * How many series one page of the grid carries.
   *
   * Unchanged from the single unpaged request this replaced, so the grid looks the same
   * and only gains the way out of it.
   */
  const SERIES_PAGE_SIZE = 100

  let keepReading = $state([])
  let onDeck = $state([])
  let recent = $state([])
  let updated = $state([])
  let allSeries = $state(null)
  /**
   * Which page of the whole catalog the grid is showing.
   *
   * The grid asked for one page of 100 and called it "all series", so a library of 3,339
   * showed its first 100 and offered no way to the rest - while `listSeries` has taken a
   * `page` since it existed. Kept here rather than in the URL because the shelves above it
   * are not paged, so a link to "the home page, page 7" would restore only half of what
   * the reader was looking at.
   */
  let seriesPage = $state(0)
  /** True while a page is in flight, so the pager cannot queue a second request. */
  let seriesBusy = $state(false)
  let error = $state(null)
  /** How many of the four shelves failed to load. Zero renders nothing. */
  let shelvesFailed = $state(0)
  /**
   * Libraries this reader can see, and which one the shelf is narrowed to.
   *
   * `null` means all of them. The choice is remembered per reader, because a reader
   * who keeps to one library should not have to narrow the shelf on every visit.
   */
  let libraries = $state([])
  let libraryId = $state(null)

  const user = $derived($session.user)
  const administrator = $derived(isAdministrator(user))

  /**
   * Searching without leaving home.
   *
   * A reader looking for one title had to change screens, and changing back lost the
   * position of the grid they were browsing. The dedicated search screen stays: it carries
   * facets, sorts and paging that do not belong above a shelf. What moved here is the
   * first keystroke.
   */
  const SEARCH_DEBOUNCE_MILLIS = 300
  const SEARCH_SIZE = 24

  let query = $state('')
  let results = $state(null)
  let searching = $state(false)
  let searchTimer = null
  /**
   * Which query the newest request belongs to.
   *
   * Typed quickly, `ab` follows `a`, and the two requests race. Without this the slower
   * one wins whenever it lands last and the reader is shown results for a query they have
   * already replaced.
   */
  let searchSerial = 0

  const searchActive = $derived(query.trim().length > 0)

  async function runSearch(term) {
    const trimmed = term.trim()
    if (!trimmed) {
      // Bumped so an in-flight request cannot deliver into an empty field.
      searchSerial += 1
      results = null
      searching = false
      return
    }
    const serial = ++searchSerial
    searching = true
    try {
      // `libraryId` is a list on this surface - the listing accepts more than one - and
      // `commonQuery` spreads it. Passing the single id this screen holds, or null, threw
      // before any request went out, so the field simply did nothing.
      const criteria = {
        query: trimmed,
        libraryId: libraryId ? [libraryId] : [],
        size: SEARCH_SIZE,
      }
      const [series, items] = await Promise.all([
        searchCatalog('series', criteria),
        searchCatalog('mediaItems', criteria),
      ])
      if (serial !== searchSerial) return
      results = { series: series.items ?? [], items: items.items ?? [] }
      error = null
    } catch (caught) {
      if (serial === searchSerial) error = caught
    } finally {
      if (serial === searchSerial) searching = false
    }
  }

  function onQuery(event) {
    query = event.target.value
    clearTimeout(searchTimer)
    const term = query
    // Cleared immediately rather than after the debounce: a reader who empties the field
    // is asking for their shelves back now, not in a third of a second.
    if (!term.trim()) {
      runSearch('')
      return
    }
    searchTimer = setTimeout(() => runSearch(term), SEARCH_DEBOUNCE_MILLIS)
  }

  async function loadLibraries() {
    // Settled with the rest: the switcher not loading must not blank the shelf, it
    // just leaves the reader unable to narrow it.
    try {
      libraries = (await listLibraries()).items ?? []
    } catch {
      libraries = []
    }
  }

  async function loadShelves() {
    // Settled rather than all-or-nothing: a reader with no progress yet gets nothing
    // useful from keep-reading, and that must not blank the rest of the page.
    const [keep, deck, added, changed] = await Promise.allSettled([
      readFeed('media-items', 'keep-reading', { libraryId }),
      readFeed('media-items', 'on-deck', { libraryId }),
      readFeed('series', 'new', { libraryId }),
      readFeed('series', 'updated', { libraryId }),
    ])
    if (keep.status === 'fulfilled') keepReading = keep.value.items ?? []
    if (deck.status === 'fulfilled') onDeck = deck.value.items ?? []
    if (added.status === 'fulfilled') recent = added.value.items ?? []
    if (changed.status === 'fulfilled') updated = changed.value.items ?? []
    // Counted and said, not swallowed. Settling the failures kept the page usable and
    // also hid a total outage: every feed answered `500` for a while, and the only
    // symptom was four permanently empty shelves - which reads as an empty library.
    // A shelf that could not be read is not a shelf with nothing on it.
    shelvesFailed = [keep, deck, added, changed].filter((r) => r.status === 'rejected').length
  }

  async function loadSeries(page = seriesPage) {
    seriesBusy = true
    try {
      const answer = await listSeries({ page, size: SERIES_PAGE_SIZE, libraryId })
      // A page can fall off the end while the reader is on it: series get removed, and a
      // library event refreshes in place. Landing on an empty grid would read as an empty
      // library, so the request is retried against the last page that still exists.
      const lastPage = Math.max(0, (answer.totalPages ?? 1) - 1)
      if (page > lastPage && (answer.items?.length ?? 0) === 0) {
        seriesPage = lastPage
        allSeries = await listSeries({ page: lastPage, size: SERIES_PAGE_SIZE, libraryId })
      } else {
        seriesPage = page
        allSeries = answer
      }
      error = null
    } catch (caught) {
      error = caught
    } finally {
      seriesBusy = false
    }
  }

  async function refresh() {
    await Promise.all([loadShelves(), loadSeries()])
  }

  function chooseLibrary(next) {
    libraryId = next
    // A different library is a different set, so the page number the reader was on means
    // nothing in it - page 7 of one library is often past the end of another.
    seriesPage = 0
    writePreference(user?.id ?? null, null, Preference.LIBRARY, next ?? '')
    refresh()
  }

  onMount(() => {
    // Empty string is a stored "all libraries"; absent means never chosen. Both land on
    // null here, but only the former survives a reload as a decision.
    const remembered = readPreference(user?.id ?? null, null, Preference.LIBRARY, '')
    libraryId = remembered === '' ? null : remembered
    loadLibraries()
    refresh()
    const offCatalog = eventHub.on(
      ['series.added', 'series.changed', 'series.removed', 'media-item.added'],
      refresh,
    )
    // Progress moves what keep-reading and on-deck contain, and only for this reader.
    const offProgress = eventHub.on(['read-progress.changed', 'series-progress.changed'], loadShelves)
    // The only trustworthy staleness signal.
    const offResync = eventHub.onResync(refresh)
    return () => {
      offCatalog()
      offProgress()
      offResync()
    }
  })

  function toggleLanguage() {
    applyLocale($locale === 'ko' ? 'en' : 'ko')
  }

  async function leave() {
    error = null
    try {
      await signOut()
    } catch (caught) {
      error = caught
    }
  }
</script>

<header>
  <span class="brand">
    <Logo size={26} label="Xoboro" />
    <h1>{$_('reader.title')}</h1>
  </span>
  <div class="actions">
    <StreamIndicator status={$streamStore} />
    {#if administrator}
      <a class="icon" href="#/admin" aria-label={$_('admin.title')} title={$_('admin.title')}>
        <Settings size={18} aria-hidden="true" />
      </a>
    {/if}
    <button class="icon" type="button" onclick={toggleLanguage} aria-label={$_('common.language')}>
      <Languages size={18} aria-hidden="true" />
    </button>
    <button class="icon" type="button" onclick={leave} aria-label={$_('common.signOut')}>
      <LogOut size={18} aria-hidden="true" />
    </button>
  </div>
</header>

<LibrarySwitcher {libraries} selected={libraryId} onchange={chooseLibrary} />

<div class="searchbar">
  <label class="visually-hidden" for="home-search">{$_('search.queryLabel')}</label>
  <input
    id="home-search"
    data-testid="home-search"
    type="search"
    value={query}
    placeholder={$_('search.queryLabel')}
    oninput={onQuery}
  />
  <!-- The dedicated screen is still one tap away, and is where the facets live. -->
  <a class="advanced" href="#/search" aria-label={$_('search.link')} title={$_('search.link')}>
    <SlidersHorizontal size={18} aria-hidden="true" />
  </a>
</div>

{#if searchActive}
  {#if results}
    {#if results.series.length === 0 && results.items.length === 0}
      <p class="waiting" data-testid="home-search-empty" role="status">
        {$_('search.results.noResults')}
      </p>
    {:else}
      <div data-testid="home-search-results">
        {#if results.series.length > 0}
          <h2 class="all">{$_('search.scope.series')}</h2>
          <ul class="grid">
            {#each results.series as found (found.id)}
              <li>
                <a href={`#/series/${found.id}`}>
                  <Cover src={artworkUrl('series', found.id)} />
                  <span class="label">{found.title ?? found.name}</span>
                  <span class="sub">
                    {$_('reader.items', { values: { count: found.mediaItemCount ?? 0 } })}
                  </span>
                </a>
              </li>
            {/each}
          </ul>
        {/if}
        {#if results.items.length > 0}
          <h2 class="all">{$_('search.scope.mediaItems')}</h2>
          <ul class="grid">
            {#each results.items as found (found.id)}
              <li>
                <a href={`#/read/${found.id}`}>
                  <Cover src={artworkUrl('mediaItem', found.id)} />
                  <span class="label">{found.title ?? found.name}</span>
                  <span class="sub">{found.seriesTitle ?? ''}</span>
                </a>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    {/if}
  {:else if searching}
    <p class="waiting" role="status">{$_('common.loading')}</p>
  {/if}
{:else}
<nav class="library" aria-label={$_('catalog.navigation')}>
  <a href="#/search">
    <Search size={18} aria-hidden="true" /><span>{$_('search.link')}</span>
  </a>
  <a href="#/collections">
    <Layers3 size={18} aria-hidden="true" /><span>{$_('catalog.collection.title')}</span>
  </a>
  <a href="#/read-lists">
    <BookOpen size={18} aria-hidden="true" /><span>{$_('catalog.readList.title')}</span>
  </a>
</nav>

<ErrorNotice {error} onretry={refresh} />

{#if shelvesFailed > 0}
  <!-- Its own notice rather than the shared one: the series grid below may have loaded
       perfectly, and reporting the whole page as broken would be as wrong as reporting
       none of it. -->
  <p class="shelves-failed" role="status" data-testid="shelves-failed">
    {$_('reader.shelvesFailed', { values: { count: shelvesFailed } })}
    <button type="button" onclick={() => loadShelves()}>{$_('common.retry')}</button>
  </p>
{/if}

<MediaShelf title={$_('reader.keepReading')} items={keepReading} kind="mediaItem" />
<MediaShelf title={$_('reader.onDeck')} items={onDeck} kind="mediaItem" />
<MediaShelf title={$_('reader.recentlyAdded')} items={recent} />
<MediaShelf title={$_('reader.recentlyUpdated')} items={updated} />

<h2 class="all">{$_('reader.allSeries')}</h2>
{#if allSeries}
  <ul class="grid">
    {#each allSeries.items as series (series.id)}
      <li>
        <a href={`#/series/${series.id}`}>
          <Cover src={artworkUrl('series', series.id)} />
          <span class="label">{series.title ?? series.name}</span>
          <span class="sub">
            {$_('reader.items', { values: { count: series.mediaItemCount ?? 0 } })}
          </span>
        </a>
      </li>
    {/each}
    {#if allSeries.items.length === 0}
      <li class="empty">{$_('reader.noSeries')}</li>
    {/if}
  </ul>
  <Pager
    page={allSeries}
    busy={seriesBusy}
    label={$_('reader.allSeries')}
    testIdPrefix="all-series-page"
    onpage={(next) => loadSeries(next)}
  />
{:else if !error}
  <p class="waiting" role="status">{$_('common.loading')}</p>
{/if}
{/if}

<style>
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: max(var(--space-4), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) var(--space-2) var(--gutter-left);
  }
  /* Groups the mark with the heading so `justify-content: space-between` on the
     header has two children to push apart rather than three. */
  .brand {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: var(--space-2);
  }
  h1 {
    min-width: 0;
    margin: 0;
    overflow: hidden;
    font-size: var(--font-lg);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: var(--space-1);
  }
  .icon {
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
  .searchbar {
    display: grid;
    grid-template-columns: minmax(0, 1fr) var(--touch-target);
    gap: var(--space-2);
    padding: 0 var(--gutter-right) var(--space-3) var(--gutter-left);
  }
  .searchbar input {
    width: 100%;
    min-width: 0;
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    background: var(--surface-inset);
    color: var(--text);
    font: inherit;
    /* 16px or iOS zooms the page on focus, which then never zooms back out. */
    font-size: var(--font-md);
  }
  .searchbar input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .advanced {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    place-items: center;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    background: var(--surface-inset);
    color: var(--text);
  }
  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .library {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(140px, 100%), 1fr));
    gap: var(--space-2);
    padding: 0 var(--gutter-right) var(--space-5) var(--gutter-left);
  }
  .library a {
    display: flex;
    min-width: 0;
    min-height: var(--touch-target);
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-inset);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  .all {
    margin: 0 0 var(--space-2);
    padding: 0 var(--gutter-right) 0 var(--gutter-left);
    font-size: var(--font-md);
  }
  .grid {
    display: grid;
    margin: 0;
    padding: 0 var(--gutter-right) max(var(--space-5), var(--inset-bottom)) var(--gutter-left);
    gap: var(--space-3);
    grid-template-columns: repeat(auto-fill, minmax(min(120px, 100%), 1fr));
    list-style: none;
  }
  .label {
    display: block;
    overflow: hidden;
    padding-top: var(--space-1);
    font-size: var(--font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sub {
    display: block;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .empty,
  .waiting {
    color: var(--text-muted);
  }
  .shelves-failed {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    margin: 0 var(--gutter-right) var(--space-4) var(--gutter-left);
    padding: var(--space-3);
    border: 1px solid var(--warning);
    border-radius: var(--radius);
    font-size: var(--font-sm);
  }
  .shelves-failed button {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-control);
    color: var(--text);
    font: inherit;
    font-size: var(--font-sm);
    cursor: pointer;
  }
</style>
