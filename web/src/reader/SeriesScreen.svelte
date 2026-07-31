<script>
  /**
   * One series and its items, in reading order.
   *
   * Reading order is what the route returns by default — ordered by number — and it is
   * deliberately not overridden. A listing sorted by title puts chapter 10 before
   * chapter 2, which is the kind of ordering bug that looks like data corruption.
   *
   * Editing metadata is offered to any authenticated caller, because the server does
   * not gate it behind an administrator: it authorizes by what the caller can already
   * see.
   */
  import { onMount } from 'svelte'
  import { ChevronLeft, PencilLine } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'
  import { artworkUrl, listSeriesMediaItems, readSeries } from '../lib/api/catalog.js'
  import { eventHub } from '../lib/eventHub.js'
  import Cover from '../components/Cover.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import SeriesMetadataForm from '../catalog/SeriesMetadataForm.svelte'

  let { params } = $props()

  let series = $state(null)
  let items = $state(null)
  let error = $state(null)
  let editing = $state(false)

  async function load() {
    try {
      const [detail, page] = await Promise.all([
        readSeries(params.id),
        listSeriesMediaItems(params.id),
      ])
      series = detail
      items = page
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(() => {
    load()
    return eventHub.on(['series.changed', 'media-item.added', 'media-item.changed'], load)
  })

  /** What a reader has done with this item, as words rather than a coloured bar alone. */
  function progressLabel(item) {
    const progress = item.readProgress
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

{#if series?.metadata?.summary}
  <p class="summary">{series.metadata.summary}</p>
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
  .summary {
    margin: 0 0 var(--space-4);
    padding: 0 var(--gutter-right) 0 var(--gutter-left);
    color: var(--text-secondary);
    font-size: var(--font-sm);
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
  .empty,
  .waiting {
    color: var(--text-muted);
  }
</style>
