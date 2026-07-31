<script>
  /**
   * Catalog history.
   *
   * Pages on the server and shows the server's total. Retention is applied when a
   * sweep runs, not when a row is read, so shortening the window does not hide rows
   * that are still stored — which means what this screen shows is what is there, and
   * an empty page means empty rather than filtered.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { listHistory } from '../lib/api/admin.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import DataTable from './DataTable.svelte'

  let page = $state(null)
  let error = $state(null)
  let busy = $state(false)

  async function load(next = 0) {
    busy = true
    try {
      page = await listHistory({ page: next })
      error = null
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  onMount(() => load(0))

  function when(millis) {
    return millis ? new Date(millis).toLocaleString() : ''
  }
</script>

<h1>{$_('admin.nav.history')}</h1>

<ErrorNotice {error} onretry={() => load(0)} />

<DataTable
  caption={$_('admin.nav.history')}
  name="history"
  columns={[$_('admin.security.when'), $_('admin.history.type'), $_('admin.history.subject')]}
  {page}
  onpage={load}
  {busy}
  emptyLabel={$_('admin.history.none')}
>
  {#snippet row(entry)}
    <tr>
      <th scope="row">{when(entry.timestampMillis ?? entry.timestamp)}</th>
      <td class="mono">{entry.type ?? ''}</td>
      <td class="mono">{entry.bookId ?? entry.seriesId ?? ''}</td>
    </tr>
  {/snippet}
</DataTable>

<style>
  h1 {
    margin: 0 0 var(--space-4);
    font-size: var(--font-xl);
  }
  .mono {
    font-family: ui-monospace, monospace;
    font-size: var(--font-xs);
  }
</style>
