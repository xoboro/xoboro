<script>
  /**
   * One series, its items, and the two ways into it.
   *
   * Chapters are listed newest first, which is where a reader following a running
   * series looks, and the order is remembered per reader and per series — a shelf
   * holding both a finished manga and a weekly webtoon wants opposite answers, so one
   * global setting would be wrong for one of them.
   *
   * Only the chapter number is ever sorted on. Sorting by title is what puts chapter
   * 10 before chapter 2, which reads as corrupted data rather than a chosen order.
   *
   * Ordering is asked of the server, never applied to the array below: the listing is
   * paged, so reversing a page here would show the oldest hundred backwards.
   *
   * Editing metadata is offered to any authenticated caller, because the server does
   * not gate it behind an administrator: it authorizes by what the caller can already
   * see.
   */
  import { onMount } from 'svelte'
  import { ChevronLeft, PencilLine } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import {
    SeriesOrder,
    artworkUrl,
    listSeriesMediaItems,
    readResumePoint,
    readSeries,
  } from '../lib/api/catalog.js'
  import { eventHub } from '../lib/eventHub.js'
  import { percentRead } from '../lib/api/progress.js'
  import { Preference, oneOf, readPreference, writePreference } from '../lib/preferences.js'
  import { session } from '../lib/session.js'
  import Cover from '../components/Cover.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import SeriesActions from './SeriesActions.svelte'
  import SeriesMetadataForm from '../catalog/SeriesMetadataForm.svelte'
  import SeriesMetadataPanel from '../catalog/SeriesMetadataPanel.svelte'
  import SeriesOrderToggle from './SeriesOrderToggle.svelte'

  let { params } = $props()

  const ORDERS = Object.values(SeriesOrder)
  const readerId = $derived($session.user?.id ?? null)

  let series = $state(null)
  let items = $state(null)
  let first = $state(null)
  let resume = $state(null)
  let error = $state(null)
  let editing = $state(false)
  let order = $state(SeriesOrder.NEWEST)

  async function load() {
    try {
      const [detail, page, resumePoint] = await Promise.all([
        readSeries(params.id),
        listSeriesMediaItems(params.id, { sort: order }),
        readResumePoint(params.id),
      ])
      series = detail
      items = page
      resume = resumePoint
      // The earliest chapter is only the head of the listing when the listing runs
      // that way; under newest-first it is on the last page, so it is asked for
      // directly rather than guessed at from whichever page arrived.
      first =
        order === SeriesOrder.OLDEST
          ? (page.items[0] ?? null)
          : ((await listSeriesMediaItems(params.id, { size: 1, sort: SeriesOrder.OLDEST }))
              .items[0] ?? null)
      error = null
    } catch (caught) {
      error = caught
    }
  }

  function choose(next) {
    order = next
    writePreference(readerId, params.id, Preference.SORT, next)
    load()
  }

  onMount(() => {
    order = oneOf(
      readPreference(readerId, params.id, Preference.SORT, SeriesOrder.NEWEST),
      ORDERS,
      SeriesOrder.NEWEST,
    )
    load()
    return eventHub.on(['series.changed', 'media-item.added', 'media-item.changed'], load)
  })

  /** What a reader has done with this item, as words rather than a coloured bar alone. */
  function progressLabel(item) {
    const progress = item.progress
    if (!progress) return $_('reader.unread')
    if (progress.completed) return $_('reader.read')
    return $_('reader.inProgress', { values: { page: progress.page } })
  }
</script>

<header>
  <a class="back" href="#/" aria-label={$_('common.back')}>
    <ChevronLeft size={22} aria-hidden="true" />
  </a>
  <h1>{series?.title ?? series?.name ?? ''}</h1>
  {#if series}
    <button
      class="icon"
      type="button"
      data-testid="edit-metadata"
      aria-label={$_('catalog.metadata.seriesTitle')}
      onclick={() => (editing = true)}
    >
      <PencilLine size={18} aria-hidden="true" />
    </button>
  {/if}
</header>

<ErrorNotice {error} onretry={load} />

{#if series}
  <!-- The cover and the facts are one element, which is what lets a wide viewport put
       them side by side. The page used to open on a title bar and a list of labels, so a
       reader arriving from a cover found nothing that looked like what they tapped. -->
  <section class="overview" data-testid="series-overview">
    <div class="art" data-testid="series-cover">
      <Cover src={artworkUrl('series', series.id)} />
    </div>
    <div class="facts" data-testid="series-facts">
      <SeriesMetadataPanel {series} />
    </div>
  </section>
{/if}

<SeriesActions {first} {resume} />

{#if items && items.items.length > 1}
  <div class="ordering">
    <SeriesOrderToggle {order} onchange={choose} />
  </div>
{/if}

{#if items}
  <ol class="items" data-testid="reading-order">
    {#each items.items as item (item.id)}
      <li>
        <a href={`#/read/${item.id}`}>
          <span class="thumb"><Cover src={artworkUrl('mediaItem', item.id)} /></span>
          <span class="detail">
            <span class="label">{item.title ?? item.name ?? item.id}</span>
            <span class="sub">{progressLabel(item)}</span>
            <!-- The words say what happened; the bar says how far. "Page 5" alone leaves a
                 reader to work out whether that is the start or nearly the end. -->
            {#if item.progress}
              {@const read = percentRead(item.progress, item.media?.pageCount)}
              <span
                class="bar"
                data-testid="item-progress"
                data-percent={read}
                role="progressbar"
                aria-valuenow={read}
                aria-valuemin="0"
                aria-valuemax="100"
                aria-label={progressLabel(item)}
              ><i style={`width:${read}%`}></i></span>
            {/if}
          </span>
        </a>
      </li>
    {/each}
    {#if items.items.length === 0}
      <li class="empty">{$_('reader.noItems')}</li>
    {/if}
  </ol>
{:else if !error}
  <p class="waiting" role="status">{$_('common.loading')}</p>
{/if}

{#if editing && series}
  <SeriesMetadataForm
    {series}
    onclose={() => (editing = false)}
    onsaved={async () => {
      editing = false
      await load()
    }}
  />
{/if}

<style>
  /* The cover keeps a fixed column and the facts take the rest, so a long publisher name
     cannot squeeze the artwork down to a sliver. It wraps on a narrow screen because
     112px of cover beside a column of labels leaves neither enough room to read. */
  .overview {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    gap: var(--space-4);
    padding: 0 var(--gutter-right) var(--space-4) var(--gutter-left);
  }
  .art {
    width: 112px;
    flex: 0 0 112px;
  }
  /* The panel inside carries the page's own gutters, which would indent it a second time
     inside this column. */
  .facts {
    min-width: min(220px, 100%);
    flex: 1 1 220px;
    margin-inline: calc(-1 * var(--gutter-left)) calc(-1 * var(--gutter-right));
  }
  header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: max(var(--space-3), calc(var(--inset-top) + var(--space-2)))
      var(--gutter-right) var(--space-2) max(var(--space-2), var(--inset-left));
  }
  .back,
  .icon {
    display: grid;
    width: var(--touch-target);
    height: var(--touch-target);
    flex: 0 0 auto;
    place-items: center;
    border: 0;
    border-radius: var(--radius-sm);
    background: none;
    color: var(--text);
    cursor: pointer;
  }
  h1 {
    min-width: 0;
    margin: 0;
    overflow: hidden;
    font-size: var(--font-lg);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* Hidden for a one-chapter series: an order control that cannot change any result
     costs a reader the time it takes to work that out. */
  .ordering {
    display: flex;
    justify-content: flex-end;
    padding: 0 var(--gutter-right) var(--space-2) var(--gutter-left);
  }
  .items {
    margin: 0;
    padding: 0 var(--gutter-right) max(var(--space-5), var(--inset-bottom)) var(--gutter-left);
    list-style: none;
  }
  .items a {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-bottom: 1px solid var(--line-subtle);
  }
  .thumb {
    display: block;
    width: 48px;
    flex: 0 0 48px;
  }
  .detail {
    min-width: 0;
  }
  .label {
    display: block;
    overflow: hidden;
    font-size: var(--font-md);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sub {
    display: block;
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  .bar {
    display: block;
    width: 100%;
    max-width: 180px;
    height: 3px;
    margin-top: var(--space-1);
    overflow: hidden;
    border-radius: var(--radius-pill);
    background: var(--surface-selected);
  }
  .bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .empty,
  .waiting {
    color: var(--text-muted);
  }
</style>
