<script>
  /**
   * Trashed entries.
   *
   * There is deliberately no restore action, and none is missing: reconciliation
   * soft-deletes what disappeared from storage, and a later scan that finds the files
   * again clears the flag by itself. A "restore" button would either do nothing or
   * lie about having put a file back.
   *
   * What this screen is for is the decision before `empty-trash`: seeing what a scan
   * removed before agreeing to lose it. Emptying lives on the Libraries screen,
   * beside the library it destroys.
   *
   * Both trashed series **and** trashed media items are listed, because emptying
   * destroys both and its confirmation counts both. A trashed item can sit under a
   * series that is perfectly healthy, so a series-only listing showed a smaller number
   * than the confirmation quoted, with no explanation for the difference.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import {
    listLibraries,
    listTrashedMediaItems,
    listTrashedSeries,
  } from '../lib/api/libraries.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import DataTable from './DataTable.svelte'

  let seriesPage = $state(null)
  let itemsPage = $state(null)
  let libraries = $state([])
  let libraryId = $state('')
  let error = $state(null)
  // Held separately per table: the two listings load independently, so a load
  // outstanding on one must not disable paging on the other.
  let seriesBusy = $state(false)
  let itemsBusy = $state(false)

  async function loadSeries(next = 0) {
    seriesBusy = true
    try {
      seriesPage = await listTrashedSeries({ libraryId: libraryId || null, page: next })
      error = null
    } catch (caught) {
      error = caught
    } finally {
      seriesBusy = false
    }
  }

  async function loadItems(next = 0) {
    itemsBusy = true
    try {
      itemsPage = await listTrashedMediaItems({ libraryId: libraryId || null, page: next })
      error = null
    } catch (caught) {
      error = caught
    } finally {
      itemsBusy = false
    }
  }

  function reload() {
    loadSeries(0)
    loadItems(0)
  }

  onMount(() => {
    listLibraries()
      .then((found) => (libraries = found))
      .catch(() => {
        libraries = []
      })
    reload()
  })
</script>

<h1>{$_('admin.nav.trash')}</h1>

<p class="hint">{$_('admin.trash.explain')}</p>

<ErrorNotice {error} onretry={reload} />

<label for="trash-library">{$_('admin.trash.filter')}</label>
<select
  id="trash-library"
  data-testid="trash-library"
  bind:value={libraryId}
  onchange={reload}
>
  <option value="">{$_('admin.trash.allLibraries')}</option>
  {#each libraries as library (library.id)}
    <option value={library.id}>{library.name}</option>
  {/each}
</select>

<h2>{$_('admin.trash.trashedSeries')}</h2>
<DataTable
  caption={$_('admin.trash.trashedSeries')}
  name="trashed-series"
  columns={[$_('admin.trash.series'), $_('admin.trash.seriesItemCount')]}
  page={seriesPage}
  onpage={loadSeries}
  busy={seriesBusy}
  emptyLabel={$_('admin.trash.noSeries')}
>
  {#snippet row(entry)}
    <tr>
      <th scope="row">{entry.title ?? entry.name ?? entry.id}</th>
      <!-- The series' own item count, which is what the listing carries. It is *not*
           the number of its items that are trashed - those are the listing below. The
           column is named for what it holds. -->
      <td>{entry.mediaItemCount ?? ''}</td>
    </tr>
  {/snippet}
</DataTable>

<h2>{$_('admin.trash.trashedItems')}</h2>
<DataTable
  caption={$_('admin.trash.trashedItems')}
  name="trashed-items"
  columns={[$_('admin.trash.item'), $_('admin.trash.itemSeries')]}
  page={itemsPage}
  onpage={loadItems}
  busy={itemsBusy}
  emptyLabel={$_('admin.trash.noItems')}
>
  {#snippet row(entry)}
    <tr>
      <th scope="row">{entry.title ?? entry.name ?? entry.id}</th>
      <td>{entry.seriesTitle ?? entry.seriesId ?? ''}</td>
    </tr>
  {/snippet}
</DataTable>

<style>
  h1 {
    margin: 0 0 var(--space-3);
    font-size: var(--font-xl);
  }
  h2 {
    margin: var(--space-5) 0 var(--space-2);
    font-size: var(--font-md);
  }
  .hint {
    margin: 0 0 var(--space-4);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  label {
    display: block;
    margin-bottom: var(--space-1);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  select {
    min-height: var(--touch-target);
    margin-bottom: var(--space-4);
    padding: 0 var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
  }
</style>
