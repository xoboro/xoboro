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

  let candidates = $state([])
  let decisions = $state([])
  let error = $state(null)
  let busy = $state(false)
  /** `{ candidate, carriers }` while the carrier list is open. */
  let inspecting = $state(null)

  async function load() {
    try {
      const [pending, decided] = await Promise.all([
        listDuplicateCandidates(),
        listDuplicateDecisions(),
      ])
      candidates = pending
      decisions = decided
      error = null
    } catch (caught) {
      error = caught
    }
  }

  onMount(load)

  async function inspect(candidate) {
    busy = true
    try {
      inspecting = { candidate, carriers: await listHashCarriers(candidate.pageHash) }
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }

  async function record(candidate, action) {
    busy = true
    try {
      await recordDuplicateDecision(candidate.pageHash, action, candidate.sizeBytes ?? null)
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
<div class="scroller">
  <table>
    <caption class="visually-hidden">{$_('admin.duplicates.candidates')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('admin.duplicates.hash')}</th>
        <th scope="col">{$_('admin.duplicates.size')}</th>
        <th scope="col">{$_('admin.duplicates.decide')}</th>
      </tr>
    </thead>
    <tbody>
      {#each candidates as candidate (candidate.pageHash)}
        <tr>
          <th scope="row">
            <button
              class="hash"
              type="button"
              disabled={busy}
              data-testid={`inspect-${candidate.pageHash}`}
              onclick={() => inspect(candidate)}
            >
              {candidate.pageHash}
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
                  data-testid={`record-${action}-${candidate.pageHash}`}
                  data-performed={UNPERFORMED_ACTIONS.includes(action) ? 'false' : 'true'}
                  onclick={() => record(candidate, action)}
                >
                  {$_(`admin.duplicates.action.${action}`)}
                </button>
              {/each}
            </div>
          </td>
        </tr>
      {/each}
      {#if candidates.length === 0}
        <tr><td colspan="3" class="empty">{$_('admin.duplicates.noCandidates')}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

<h2>{$_('admin.duplicates.decisions')}</h2>
<div class="scroller">
  <table>
    <caption class="visually-hidden">{$_('admin.duplicates.decisions')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('admin.duplicates.hash')}</th>
        <th scope="col">{$_('admin.duplicates.recorded')}</th>
        <th scope="col">{$_('admin.duplicates.effect')}</th>
      </tr>
    </thead>
    <tbody>
      {#each decisions as decision (decision.pageHash)}
        <tr>
          <th scope="row">{decision.pageHash}</th>
          <td>{$_(`admin.duplicates.action.${decision.action}`)}</td>
          <td>
            {#if UNPERFORMED_ACTIONS.includes(decision.action)}
              <span class="not-performed" data-testid={`not-performed-${decision.pageHash}`}>
                {$_('admin.duplicates.intentOnly')}
              </span>
            {:else}
              <span>{$_('admin.duplicates.excludedFromList')}</span>
            {/if}
          </td>
        </tr>
      {/each}
      {#if decisions.length === 0}
        <tr><td colspan="3" class="empty">{$_('admin.duplicates.noDecisions')}</td></tr>
      {/if}
    </tbody>
  </table>
</div>

{#if inspecting}
  <Dialog
    title={$_('admin.duplicates.carriersTitle')}
    onclose={() => (inspecting = null)}
  >
    {#snippet children()}
      <p class="hash-detail">{inspecting.candidate.pageHash}</p>
      <ul>
        {#each inspecting.carriers as carrier, index (`${carrier.mediaItemId}-${carrier.pageNumber}-${index}`)}
          <li>
            <span>{carrier.mediaItemTitle ?? carrier.mediaItemId}</span>
            <span class="page">{$_('admin.duplicates.page', { values: { number: carrier.pageNumber } })}</span>
          </li>
        {/each}
        {#if inspecting.carriers.length === 0}
          <li class="empty">{$_('admin.duplicates.noCarriers')}</li>
        {/if}
      </ul>
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
  .scroller {
    overflow-x: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--font-sm);
  }
  th,
  td {
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--line-subtle);
    text-align: left;
  }
  thead th {
    color: var(--text-muted);
    font-size: var(--font-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
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
  .page {
    color: var(--text-muted);
  }
  .empty {
    color: var(--text-muted);
  }
</style>
