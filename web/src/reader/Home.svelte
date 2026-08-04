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
  import { Languages, Layers3, BookOpen, LogOut, Search, Settings } from '@lucide/svelte'
  import { _, applyLocale, locale } from '../lib/i18n.js'
  import { isAdministrator, session, signOut } from '../lib/session.js'
  import { artworkUrl, listSeries, readFeed } from '../lib/api/catalog.js'
  import { eventHub } from '../lib/eventHub.js'
  import Cover from '../components/Cover.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Logo from '../components/Logo.svelte'
  import MediaShelf from '../components/MediaShelf.svelte'
  import StreamIndicator from '../components/StreamIndicator.svelte'

  const streamStore = eventHub.status

  let keepReading = $state([])
  let onDeck = $state([])
  let recent = $state([])
  let updated = $state([])
  let allSeries = $state(null)
  let error = $state(null)
  /** How many of the four shelves failed to load. Zero renders nothing. */
  let shelvesFailed = $state(0)

  const user = $derived($session.user)
  const administrator = $derived(isAdministrator(user))

  async function loadShelves() {
    // Settled rather than all-or-nothing: a reader with no progress yet gets nothing
    // useful from keep-reading, and that must not blank the rest of the page.
    const [keep, deck, added, changed] = await Promise.allSettled([
      readFeed('media-items', 'keep-reading'),
      readFeed('media-items', 'on-deck'),
      readFeed('series', 'new'),
      readFeed('series', 'updated'),
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

  async function loadSeries() {
    try {
      allSeries = await listSeries({ size: 100 })
      error = null
    } catch (caught) {
      error = caught
    }
  }

  async function refresh() {
    await Promise.all([loadShelves(), loadSeries()])
  }

  onMount(() => {
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
{:else if !error}
  <p class="waiting" role="status">{$_('common.loading')}</p>
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
