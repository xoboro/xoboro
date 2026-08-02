<script>
  /**
   * Duplicate pages.
   *
   * The honesty requirement is the whole design of this screen. Xoboro **records**
   * decisions and does not perform removal: nothing executes `DELETE_AUTO` or
   * `DELETE_MANUAL`, because removing a page means rewriting an archive on disk —
   * destructive, irreversible, and a decision belonging to whoever owns those files.
   * `deleteCount` is always `0`, and that is the stored value rather than a
   * placeholder.
   *
   * So the actions are labelled as recording an intention, the screen says removal is
   * not carried out, and there is no button anywhere that implies deletion happens.
   * A button labelled "Delete pages" that deletes nothing would be a lie in the
   * interface.
   *
   * `IGNORE` is the one action with a real effect: the candidate list excludes any
   * hash with a recorded decision, so ignoring one removes it from the list for good.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import {
    DUPLICATE_ACTIONS,
    UNPERFORMED_ACTIONS,
    listDuplicateCandidates,
    listDuplicateDecisions,
    listHashCarriers,
    recordDuplicateDecision,
  } from '../lib/api/admin.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Dialog from '../components/Dialog.svelte'
  import DataTable from './DataTable.svelte'

  /**
   * Both listings are server-paged, so both hold the page envelope rather than an
   * array and both render through the console's one list pattern.
   */
  let candidates = $state(null)
  let decisions = $state(null)
  let error = $state(null)
  let busy = $state(false)
  /**
   * The page each listing is *asked* for, held separately from the page it last returned.
   *
   * Reading the requested page off the committed envelope (`decisions?.page ?? 0`) was
   * wrong while a load was outstanding: paging one table read the other's pre-request
   * value, so clicking Next on both in quick succession sent the second table forward and
   * silently pulled the first one back. Nothing indicated the loss, because `load` never
   * set `busy` and the paging buttons are gated only on `hasPrevious`/`hasNext`.
   */
  let candidatePage = $state(0)
  let decisionPage = $state(0)
  /**
   * Discards a response that is no longer the newest request.
   *
   * Two loads in flight can land in either order, and the older one committing last would
   * put the screen on a page nobody asked for. Only the newest may commit.
   */
  let loadToken = 0
  /** `{ candidate, carriers }` while the carrier list is open; `carriers` is a page. */
  let inspecting = $state(null)

  /**
   * Clamps a page request to one that still exists.
   *
   * Recording a decision removes the hash from the candidate list, so deciding the last
   * row of the last page shrinks the listing by a page. Re-reading at the page the screen
   * was on then returns an empty one, and the operator is left looking at an empty table
   * with the remaining candidates hidden behind a Previous button they have no reason to
   * suspect. The listing is re-asked for the last page that exists instead.
   */
  async function readPage(fetchPage, requested) {
    const first = await fetchPage(requested)
    const last = Math.max(0, (first.totalPages ?? 1) - 1)
    if (requested <= last) return first
    // Clamped even when the listing came back empty. Returning the out-of-range page
    // because there is nothing to show anyway left the paging summary reading "0 of 0 ·
    // page 2 of 1" — the one case where an operator is most likely to be looking at the
    // indicator to work out where their rows went.
    return fetchPage(last)
  }

  async function load() {
    const token = ++loadToken
    // Set unconditionally: a second click while this request is outstanding is exactly
    // what disables the paging buttons (see DataTable's `busy`), so both tables go busy
    // for the duration of any load, not just this one's own.
    busy = true
    try {
      const [pending, decided] = await Promise.all([
        readPage((page) => listDuplicateCandidates({ page }), candidatePage),
        readPage((page) => listDuplicateDecisions({ page }), decisionPage),
      ])
      if (token !== loadToken) return
      candidates = pending
      decisions = decided
      // Re-synced to what came back, because `readPage` may have clamped a request past
      // the end. Leaving the requested page out of range would make the next paging click
      // move relative to a page that does not exist.
      candidatePage = pending.page ?? candidatePage
      decisionPage = decided.page ?? decisionPage
      error = null
    } catch (caught) {
      if (token !== loadToken) return
      error = caught
    } finally {
      // Only the newest load may clear `busy`, for the same reason only the newest may
      // commit its data: an older load finishing after a newer one has started would
      // otherwise re-enable paging while that newer request is still outstanding.
      if (token === loadToken) busy = false
    }
  }

  /** Moves one listing without touching the other's requested page. */
  function goToCandidatePage(next) {
    candidatePage = next
    return load()
  }

  function goToDecisionPage(next) {
    decisionPage = next
    return load()
  }

  onMount(load)

  async function inspect(candidate) {
    busy = true
    try {
      inspecting = { candidate, carriers: await listHashCarriers(candidate.hash) }
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  async function record(candidate, action) {
    busy = true
    try {
      await recordDuplicateDecision(candidate.hash, action, candidate.sizeBytes ?? null)
      // Recording removes the hash from the candidate list, so a decision taken on the
      // last row of a page would otherwise leave the operator on a page that no longer
      // exists. Both listings are re-read at their current page.
      await load()
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }
</script>

<h1>{$_('admin.nav.duplicates')}</h1>

<!-- Stated once, prominently, rather than buried in a tooltip on each action. -->
<p class="disclosure" data-testid="removal-disclosure">
  {$_('admin.duplicates.removalNotPerformed')}
</p>

<ErrorNotice {error} onretry={load} />

<h2>{$_('admin.duplicates.candidates')}</h2>
<DataTable
  caption={$_('admin.duplicates.candidates')}
  name="candidates"
  columns={[
    $_('admin.duplicates.hash'),
    $_('admin.duplicates.size'),
    $_('admin.duplicates.decide'),
  ]}
  page={candidates}
  onpage={goToCandidatePage}
  {busy}
  emptyLabel={$_('admin.duplicates.noCandidates')}
  keyOf={(candidate) => candidate.hash}
>
  {#snippet row(candidate)}
    <tr>
      <th scope="row">
        <button
          class="hash"
          type="button"
          disabled={busy}
          data-testid={`inspect-${candidate.hash}`}
          onclick={() => inspect(candidate)}
        >
          {candidate.hash}
        </button>
      </th>
      <td>
        <!-- A hash whose pages differ in size has no single size, and the listing
             reports null for it. Rendering "0 bytes" would invent a fact. -->
        {candidate.sizeBytes ?? $_('admin.duplicates.mixedSize')}
      </td>
      <td>
        <div class="actions">
          {#each DUPLICATE_ACTIONS as action (action)}
            <button
              type="button"
              disabled={busy}
              data-testid={`record-${action}-${candidate.hash}`}
              data-performed={UNPERFORMED_ACTIONS.includes(action) ? 'false' : 'true'}
              onclick={() => record(candidate, action)}
            >
              {$_(`admin.duplicates.action.${action}`)}
            </button>
          {/each}
        </div>
      </td>
    </tr>
  {/snippet}
</DataTable>

<h2>{$_('admin.duplicates.decisions')}</h2>
<DataTable
  caption={$_('admin.duplicates.decisions')}
  name="decisions"
  columns={[
    $_('admin.duplicates.hash'),
    $_('admin.duplicates.recorded'),
    $_('admin.duplicates.effect'),
  ]}
  page={decisions}
  onpage={goToDecisionPage}
  {busy}
  emptyLabel={$_('admin.duplicates.noDecisions')}
  keyOf={(decision) => decision.hash}
>
  {#snippet row(decision)}
    <tr>
      <th scope="row">{decision.hash}</th>
      <td>{$_(`admin.duplicates.action.${decision.action}`)}</td>
      <td>
        {#if UNPERFORMED_ACTIONS.includes(decision.action)}
          <span class="not-performed" data-testid={`not-performed-${decision.hash}`}>
            {$_('admin.duplicates.intentOnly')}
          </span>
        {:else}
          <span>{$_('admin.duplicates.excludedFromList')}</span>
        {/if}
      </td>
    </tr>
  {/snippet}
</DataTable>

{#if inspecting}
  <Dialog
    title={$_('admin.duplicates.carriersTitle')}
    onclose={() => (inspecting = null)}
  >
    {#snippet children()}
      <p class="hash-detail">{inspecting.candidate.hash}</p>
      <ul>
        {#each inspecting.carriers.items ?? [] as carrier, index (`${carrier.mediaItemId}-${carrier.pageNumber}-${index}`)}
          <li data-testid={`carrier-${carrier.mediaItemId}-${carrier.pageNumber}`}>
            <!-- The entry name, because deciding whether a repeated page is a scanner
                 credit is a judgement about the file. This read `mediaItemTitle`, which no
                 response carries, so every carrier fell through to the identifier and the
                 dialog listed opaque ids while the server was sending the name. The
                 identifier stays as the fallback: a name is what the route sends, not
                 something it promises. -->
            <span class="carrier-file">{carrier.fileName ?? carrier.mediaItemId}</span>
            <span class="page">
              {$_('admin.duplicates.page', { values: { number: carrier.pageNumber } })}
            </span>
          </li>
        {/each}
        {#if (inspecting.carriers.items ?? []).length === 0}
          <li class="empty">{$_('admin.duplicates.noCarriers')}</li>
        {/if}
      </ul>
      {#if (inspecting.carriers.totalItems ?? 0) > (inspecting.carriers.items ?? []).length}
        <!-- Said rather than silently truncated: a hash carried by more pages than one
             listing holds would otherwise look like the whole answer. -->
        <p class="more" data-testid="carriers-truncated">
          {$_('admin.duplicates.carriersTruncated', {
            values: {
              shown: (inspecting.carriers.items ?? []).length,
              total: inspecting.carriers.totalItems,
            },
          })}
        </p>
      {/if}
    {/snippet}
  </Dialog>
{/if}

<style>
  h1 {
    margin: 0 0 var(--space-3);
    font-size: var(--font-xl);
  }
  h2 {
    margin: var(--space-5) 0 var(--space-2);
    font-size: var(--font-md);
  }
  .disclosure {
    margin: 0 0 var(--space-4);
    padding: var(--space-3);
    border: 1px solid var(--warning);
    border-radius: var(--radius);
    font-size: var(--font-sm);
  }
  /* The table chrome lives in DataTable now; only what is specific to these rows is
     here. Scoping still applies: a snippet's markup is authored in this file, so it
     carries this component's scope even though DataTable renders it. */
  .hash {
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent-text);
    font: inherit;
    font-family: ui-monospace, monospace;
    text-decoration: underline;
    cursor: pointer;
  }
  .hash-detail {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-family: ui-monospace, monospace;
    font-size: var(--font-sm);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .actions button {
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
  .not-performed {
    color: var(--warning);
  }
  .more {
    margin: var(--space-3) 0 0;
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-bottom: 1px solid var(--line-subtle);
    font-size: var(--font-sm);
  }
  .carrier-file {
    font-family: ui-monospace, monospace;
    overflow-wrap: anywhere;
  }
  .page {
    color: var(--text-muted);
    white-space: nowrap;
  }
  .empty {
    color: var(--text-muted);
  }
</style>
