<script>
  /**
   * Renders DataTable with a row snippet, so it can be tested outside a console screen.
   *
   * The `row` prop is a snippet, which cannot be written from a plain test file. Passing
   * `name` straight through is what lets a test render the table without one.
   */
  import DataTable from '../../src/admin/DataTable.svelte'

  let { page = null, name = undefined, onpage = () => {} } = $props()
</script>

<DataTable
  caption="Harness listing"
  {name}
  {onpage}
  {page}
  columns={['Identifier']}
  emptyLabel="Nothing here"
  keyOf={(item) => item.id}
>
  {#snippet row(item)}
    <tr><th scope="row" data-testid={`row-${item.id}`}>{item.id}</th></tr>
  {/snippet}
</DataTable>
