<script>
  /**
   * Edits a series' metadata.
   *
   * Two things this form is careful about, both of which are easy to get wrong in a
   * way that reports success:
   *
   * 1. **Emptying a field is not sending `null`.** Half these fields treat `null` as
   *    "clear" and half treat it as "leave alone", so the form asks for
   *    {@link CLEAR} and lets `buildMetadataPatch` decide what that means per field.
   * 2. **A lock is not write protection.** The server stores locks for the
   *    metadata-refresh pipeline to respect; they do not stop a manual patch. So the
   *    checkbox says what it actually does — it protects the field from being
   *    overwritten by a refresh — and does not disable the input beside it.
   *
   * Only edited fields are submitted. An untouched field is absent from the body,
   * which is what preserves it.
   */
  import { onMount } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { CLEAR, patchSeriesMetadata, readSeriesMetadata } from '../lib/api/metadata.js'
  import Dialog from '../components/Dialog.svelte'
  import ErrorNotice from '../components/ErrorNotice.svelte'

  let { series, onsaved, onclose } = $props()

  const STATUSES = ['ONGOING', 'ENDED', 'HIATUS', 'ABANDONED']
  const DIRECTIONS = ['LEFT_TO_RIGHT', 'RIGHT_TO_LEFT', 'VERTICAL', 'WEBTOON']

  /**
   * What is stored, read from the surface that has it.
   *
   * This was `series.metadata ?? {}`, and no route has ever sent a `metadata` object on
   * a series: `GET /series/{id}` carries the same values flat and no locks at all. So
   * `stored` was always `{}`, every field opened blank, and every lock read as clear.
   *
   * Blank fields mostly hid the problem, because "unchanged is absent" coincides with
   * an empty input — except for `status`, which opens on a value from a fixed list. An
   * absent `stored.status` made the first entry count as an edit, so opening this
   * dialog on an ENDED series and pressing save with nothing else touched reset it to
   * ONGOING. Reading what is stored is the only thing that makes "what changed" a
   * question with an answer, so the form waits for it rather than guessing.
   */
  let stored = $state(null)

  let title = $state('')
  let summary = $state('')
  let publisher = $state('')
  let language = $state('')
  let status = $state(STATUSES[0])
  let readingDirection = $state('')
  let ageRating = $state('')
  let genres = $state('')
  let tags = $state('')

  let titleLock = $state(false)
  let summaryLock = $state(false)
  let genresLock = $state(false)
  let tagsLock = $state(false)

  let busy = $state(false)
  let error = $state(null)

  onMount(async () => {
    try {
      const found = await readSeriesMetadata(series.id)
      title = found.title ?? ''
      summary = found.summary ?? ''
      publisher = found.publisher ?? ''
      language = found.language ?? ''
      status = found.status ?? STATUSES[0]
      readingDirection = found.readingDirection ?? ''
      ageRating = found.ageRating ?? ''
      genres = (found.genres ?? []).join(', ')
      tags = (found.tags ?? []).join(', ')
      titleLock = Boolean(found.titleLock)
      summaryLock = Boolean(found.summaryLock)
      genresLock = Boolean(found.genresLock)
      tagsLock = Boolean(found.tagsLock)
      // Assigned last, because it is what lets the form be edited and submitted: no
      // patch can be computed against a baseline that has not arrived.
      stored = found
    } catch (caught) {
      error = caught
    }
  })

  function list(value) {
    return value
      .split(',')
      .map((entry) => entry.trim())
      .filter(Boolean)
  }

  /**
   * Whether two lists hold the same entries in the same order.
   *
   * Compared element by element rather than by joining on a separator. A separator
   * has to be a character no entry can contain, and the previous version reached for
   * a literal NUL byte to guarantee that — which put a raw control byte in the source
   * file, where it makes the file binary to git and to grep and is invisible in an
   * editor. There is no separator to pick if nothing is joined.
   */
  function sameList(left, right) {
    return left.length === right.length && left.every((entry, index) => entry === right[index])
  }

  /** A string field: unchanged is absent, emptied is CLEAR, otherwise the value. */
  function stringEdit(current, original) {
    const next = current.trim()
    const before = (original ?? '').trim()
    if (next === before) return undefined
    return next === '' ? CLEAR : next
  }

  function edits() {
    const changes = {}
    const put = (field, value) => {
      if (value !== undefined) changes[field] = value
    }

    put('title', stringEdit(title, stored.title))
    put('summary', stringEdit(summary, stored.summary))
    put('publisher', stringEdit(publisher, stored.publisher))
    put('language', stringEdit(language, stored.language))
    if (status !== stored.status) put('status', status)

    // A patch field: empty means CLEAR, which becomes an explicit null.
    if (readingDirection !== (stored.readingDirection ?? '')) {
      put('readingDirection', readingDirection === '' ? CLEAR : readingDirection)
    }
    if (String(ageRating) !== String(stored.ageRating ?? '')) {
      put('ageRating', ageRating === '' ? CLEAR : Number(ageRating))
    }

    const nextGenres = list(genres)
    if (!sameList(nextGenres, stored.genres ?? [])) {
      put('genres', nextGenres.length ? nextGenres : CLEAR)
    }
    const nextTags = list(tags)
    if (!sameList(nextTags, stored.tags ?? [])) {
      put('tags', nextTags.length ? nextTags : CLEAR)
    }

    // Locks are plain booleans and always sent when changed.
    if (titleLock !== Boolean(stored.titleLock)) put('titleLock', titleLock)
    if (summaryLock !== Boolean(stored.summaryLock)) put('summaryLock', summaryLock)
    if (genresLock !== Boolean(stored.genresLock)) put('genresLock', genresLock)
    if (tagsLock !== Boolean(stored.tagsLock)) put('tagsLock', tagsLock)

    return changes
  }

  async function submit(event) {
    event.preventDefault()
    // Without a baseline every field would read as an edit, so a save before the read
    // lands is exactly the overwrite this form exists to avoid.
    if (busy || !stored) return
    const changes = edits()
    if (Object.keys(changes).length === 0) {
      // Nothing to say. Sending an empty patch would answer 200 and mean nothing,
      // which reads as a successful save that changed something.
      onclose()
      return
    }
    busy = true
    error = null
    try {
      const updated = await patchSeriesMetadata(series.id, changes)
      onsaved(updated)
    } catch (caught) {
      error = caught
    } finally {
      busy = false
    }
  }
</script>

<Dialog title={$_('catalog.metadata.seriesTitle')} {onclose}>
  {#snippet children()}
    <!-- Held back until the stored values arrive. Fields shown before then would be
         blank, and a blank field that reads as an edit is how a save came to overwrite
         what it was meant to preserve. -->
    {#if !stored}
      <p class="waiting" role="status">{$_('common.loading')}</p>
      <ErrorNotice {error} />
    {:else}
    <form id="series-metadata" onsubmit={submit} novalidate>
      <p class="lock-note" data-testid="lock-note">{$_('catalog.metadata.lockNote')}</p>

      <label for="md-title">{$_('catalog.metadata.title')}</label>
      <input id="md-title" data-testid="md-title" bind:value={title} />
      <label class="check">
        <input type="checkbox" data-testid="md-title-lock" bind:checked={titleLock} />
        {$_('catalog.metadata.lockField')}
      </label>

      <label for="md-summary">{$_('catalog.metadata.summary')}</label>
      <textarea id="md-summary" rows="4" data-testid="md-summary" bind:value={summary}></textarea>
      <label class="check">
        <input type="checkbox" bind:checked={summaryLock} />
        {$_('catalog.metadata.lockField')}
      </label>

      <label for="md-status">{$_('catalog.metadata.status')}</label>
      <select id="md-status" data-testid="md-status" bind:value={status}>
        {#each STATUSES as value (value)}
          <option {value}>{value}</option>
        {/each}
      </select>

      <label for="md-direction">{$_('catalog.metadata.readingDirection')}</label>
      <select id="md-direction" data-testid="md-direction" bind:value={readingDirection}>
        <option value="">{$_('catalog.metadata.unset')}</option>
        {#each DIRECTIONS as value (value)}
          <option {value}>{value}</option>
        {/each}
      </select>

      <label for="md-publisher">{$_('catalog.metadata.publisher')}</label>
      <input id="md-publisher" bind:value={publisher} />

      <label for="md-language">{$_('catalog.metadata.language')}</label>
      <input id="md-language" bind:value={language} />

      <label for="md-age">{$_('catalog.metadata.ageRating')}</label>
      <input id="md-age" type="number" min="0" data-testid="md-age" bind:value={ageRating} />

      <label for="md-genres">{$_('catalog.metadata.genres')}</label>
      <input id="md-genres" data-testid="md-genres" bind:value={genres} />
      <label class="check">
        <input type="checkbox" bind:checked={genresLock} />
        {$_('catalog.metadata.lockField')}
      </label>

      <label for="md-tags">{$_('catalog.metadata.tags')}</label>
      <input id="md-tags" data-testid="md-tags" bind:value={tags} />
      <label class="check">
        <input type="checkbox" bind:checked={tagsLock} />
        {$_('catalog.metadata.lockField')}
      </label>

      <ErrorNotice {error} />
    </form>
    {/if}
  {/snippet}

  {#snippet footer()}
    <button type="button" onclick={onclose}>{$_('common.cancel')}</button>
    <button
      class="primary"
      type="submit"
      form="series-metadata"
      data-testid="md-save"
      disabled={busy}
    >
      {busy ? $_('common.saving') : $_('common.save')}
    </button>
  {/snippet}
</Dialog>

<style>
  form {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }
  .lock-note {
    margin: 0 0 var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: var(--font-xs);
  }
  label {
    margin-top: var(--space-2);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  input:not([type='checkbox']),
  select,
  textarea {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
    font: inherit;
    font-size: var(--font-input);
  }
  input:not([type='checkbox']),
  select {
    min-height: var(--touch-target);
  }
  .check {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-xs);
    font-weight: 400;
  }
  .primary {
    min-height: var(--touch-target);
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  .primary:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
