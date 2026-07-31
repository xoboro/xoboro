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
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { listLibraries, listTrashed } from '../lib/api/libraries.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import DataTable from './DataTable.svelte'

  let page = $state(null)
  let libraries = $state([])
  let libraryId = $state('')
  let error = $state(null)

  async function load(next = 0) {
    try {
      page = await listTrashed({ libraryId: libraryId || null, page: next })
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(() => {
    listLibraries()
      .then((found) => (libraries = found))
      .catch(() => {
        libraries = []
      })
    load(0)
  })
</script>

<h1>{$_('admin.nav.trash')}</h1>

<p class="hint">{$_('admin.trash.explain')}</p>

<ErrorNotice {error} onretry={() => load(0)} />

<label for="trash-library">{$_('admin.trash.filter')}</label>
<select
  id="trash-library"
  data-testid="trash-library"
  bind:value={libraryId}
  onchange={() => load(0)}
>
  <option value="">{$_('admin.trash.allLibraries')}</option>
  {#each libraries as library (library.id)}
    <option value={library.id}>{library.name}</option>
  {/each}
</select>

<DataTable
  caption={$_('admin.nav.trash')}
  columns={[$_('admin.trash.series'), $_('admin.trash.items')]}
  {page}
  onpage={load}
  emptyLabel={$_('admin.trash.none')}
>
  {#snippet row(entry)}
    <tr>
      <th scope="row">{entry.title ?? entry.name ?? entry.id}</th>
      <td>{entry.mediaItemCount ?? ''}</td>
    </tr>
  {/snippet}
</DataTable>

<style>
  h1 {
    margin: 0 0 var(--space-3);
    font-size: var(--font-xl);
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
