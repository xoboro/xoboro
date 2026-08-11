<script>
  /**
   * What is known about a series, for reading rather than editing.
   *
   * The screen previously showed one field — the summary — while the route returns
   * status, publisher, language, age rating, genres, tags, a total book count and
   * links. Everything the importers had gathered from `series.json` and `ComicInfo.xml`
   * arrived and was thrown away at the last step, which is indistinguishable from an
   * importer that never ran.
   *
   * Empty fields are omitted rather than shown blank. A definition list of eight
   * labels with "—" beside each reads as a broken page; the absence of a row is the
   * honest rendering of an absent value.
   *
   * Locks are not shown. They govern what a metadata refresh may overwrite, which is a
   * concern for whoever edits the series, and the edit form already renders them.
   */
  import { _, locale } from '../lib/i18n.js'

  let {
    /** The series, as `/series/{id}` returns it. */
    series,
  } = $props()

  /**
   * Read flat, off the series itself.
   *
   * This component used to read `series.metadata`, which the series route has never
   * sent: the nested shape with locks and a `totalBookCount` is what
   * `PATCH /series/{id}/metadata` *answers* with, and there is no `GET` beside it. So
   * the sub-object was always `undefined` and the whole panel rendered nothing — the
   * failure mode of a metadata screen that shows no metadata is indistinguishable from
   * a catalogue that has none, which is why it survived.
   */
  const known = $derived(series ?? null)

  /**
   * Roles come from sidecar files the server did not author, so the set is open.
   * An unrecognised role is printed as it arrived rather than translated away or used
   * as grounds to hide the author, because the fact that the person worked on this is
   * the part a reader cares about.
   */
  const ROLES = Object.freeze([
    'writer',
    'penciller',
    'inker',
    'colorist',
    'letterer',
    'cover',
    'editor',
    'translator',
  ])
  function roleLabel(role) {
    const value = (role ?? '').toLowerCase()
    return ROLES.includes(value) ? $_(`catalog.metadata.display.roles.${value}`) : (role ?? '')
  }

  const authors = $derived(known?.authors ?? [])

  const alternateTitles = $derived(known?.alternateTitles ?? [])

  /**
   * A language as a reader would name it.
   *
   * The route sends what the sidecar stored — `ko`, `ja` — which is a tag and not a name.
   * `Intl.DisplayNames` throws on a malformed tag and returns the input for a well-formed
   * one it does not know, and the value comes from a library file this server did not
   * author, so both outcomes fall back to printing what arrived. Dropping the row instead
   * would lose the fact that a language was recorded.
   */
  function languageName(code, activeLocale) {
    if (!code) return ''
    try {
      return new Intl.DisplayNames([activeLocale], { type: 'language' }).of(code) || code
    } catch {
      return code
    }
  }

  /**
   * How many items are here, and how many the source says there should be.
   *
   * Reporting only the first makes a part-scanned series read as complete; reporting
   * only the second promises chapters that cannot be opened. They are one row, and the
   * expected count appears only when it says something the present count does not.
   */
  const itemCount = $derived.by(() => {
    const present = known?.mediaItemCount
    if (present == null) return ''
    const expected = known?.expectedMediaItemCount
    // "0 chapters" is not something a series page needs to say; "0 of 20" is, because
    // it says the scan has not reached them yet.
    if (present === 0 && !expected) return ''
    return expected != null && expected !== present
      ? $_('catalog.metadata.display.bookCountOfExpected', {
          values: { count: present, expected },
        })
      : $_('catalog.metadata.display.bookCount', { values: { count: present } })
  })

  /** Text rows, in the order a reader scans them. Blank values drop out. */
  const rows = $derived(
    [
      ['status', known?.status ? $_(`catalog.metadata.statuses.${known.status}`) : ''],
      ['publisher', known?.publisher ?? ''],
      ['language', languageName(known?.language, $locale)],
      [
        'ageRating',
        known?.ageRating == null
          ? ''
          : $_('catalog.metadata.display.ageRatingValue', {
              values: { age: known.ageRating },
            }),
      ],
      ['books', itemCount],
    ].filter(([, value]) => value !== ''),
  )

  const chipGroups = $derived(
    [
      ['genres', [...(known?.genres ?? [])]],
      ['tags', [...(known?.tags ?? [])]],
    ].filter(([, values]) => values.length > 0),
  )

  const links = $derived(known?.links ?? [])

  /**
   * Whether there is anything at all to show.
   *
   * A series that was indexed but never refreshed carries empty strings rather than an
   * absent object, so "is there a metadata object" is not the question — "is any of it
   * filled in" is. A zero item count is not content: every series has one.
   */
  const anything = $derived(
    Boolean(known?.summary) ||
      rows.length > 0 ||
      authors.length > 0 ||
      chipGroups.length > 0 ||
      alternateTitles.length > 0 ||
      links.length > 0,
  )
</script>

{#if anything}
  {#if known.summary}
    <p class="summary" data-testid="series-summary">{known.summary}</p>
  {/if}

  {#if authors.length > 0}
    <dl class="facts" data-testid="series-authors">
      {#each authors as author, at (`${author.role}:${author.name}:${at}`)}
        <div class="fact">
          <dt>{roleLabel(author.role)}</dt>
          <dd>{author.name}</dd>
        </div>
      {/each}
    </dl>
  {/if}

  {#if rows.length > 0}
    <dl class="facts" data-testid="series-facts">
      {#each rows as [key, value] (key)}
        <div class="fact">
          <dt>{$_(`catalog.metadata.display.${key}`)}</dt>
          <dd>{value}</dd>
        </div>
      {/each}
    </dl>
  {/if}

  {#each chipGroups as [key, values] (key)}
    <section class="chips" data-testid={`series-${key}`}>
      <h2>{$_(`catalog.metadata.display.${key}`)}</h2>
      <ul>
        {#each values as value (value)}
          <li>{value}</li>
        {/each}
      </ul>
    </section>
  {/each}

  {#if alternateTitles.length > 0}
    <section class="chips" data-testid="series-alternate-titles">
      <h2>{$_('catalog.metadata.display.alternateTitles')}</h2>
      <ul>
        {#each alternateTitles as alternate, at (`${alternate.label}:${alternate.title}:${at}`)}
          <!-- The label is what distinguishes one alternate from another - a romanisation
               from a short form - so it is shown rather than used only as a key. -->
          <li>{alternate.label ? `${alternate.label}: ${alternate.title}` : alternate.title}</li>
        {/each}
      </ul>
    </section>
  {/if}

  {#if links.length > 0}
    <section class="chips" data-testid="series-links">
      <h2>{$_('catalog.metadata.display.links')}</h2>
      <ul>
        {#each links as link (link.url)}
          <li>
            <!-- noopener because these URLs come from a sidecar in the library, which
                 is data the server did not author. -->
            <a href={link.url} target="_blank" rel="noopener noreferrer">{link.label || link.url}</a>
          </li>
        {/each}
      </ul>
    </section>
  {/if}
{/if}

<style>
  .summary {
    margin: 0 0 var(--space-4);
    padding: 0 var(--gutter-right) 0 var(--gutter-left);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    line-height: 1.6;
  }
  .facts {
    display: grid;
    margin: 0 0 var(--space-4);
    padding: 0 var(--gutter-right) 0 var(--gutter-left);
    gap: var(--space-2);
  }
  .fact {
    display: flex;
    gap: var(--space-3);
    font-size: var(--font-sm);
  }
  dt {
    flex: 0 0 6.5rem;
    color: var(--text-muted);
  }
  dd {
    min-width: 0;
    margin: 0;
    color: var(--text);
  }
  .chips {
    margin: 0 0 var(--space-4);
    padding: 0 var(--gutter-right) 0 var(--gutter-left);
  }
  .chips h2 {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-xs);
    font-weight: 400;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .chips ul {
    display: flex;
    margin: 0;
    padding: 0;
    flex-wrap: wrap;
    gap: var(--space-2);
    list-style: none;
  }
  .chips li {
    padding: var(--space-1) var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius-pill);
    background: var(--surface-raised);
    color: var(--text-secondary);
    font-size: var(--font-xs);
  }
  .chips a {
    color: var(--accent-text);
  }
</style>
