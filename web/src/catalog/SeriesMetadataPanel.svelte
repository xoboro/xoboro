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
  import { _ } from '../lib/i18n.js'

  let {
    /** The series, as `/series/{id}` returns it. */
    series,
  } = $props()

  const metadata = $derived(series?.metadata ?? null)

  /** Text rows, in the order a reader scans them. Blank values drop out. */
  const rows = $derived(
    [
      ['status', metadata?.status ? $_(`catalog.metadata.statuses.${metadata.status}`) : ''],
      ['publisher', metadata?.publisher ?? ''],
      ['language', metadata?.language ?? ''],
      [
        'ageRating',
        metadata?.ageRating == null
          ? ''
          : $_('catalog.metadata.display.ageRatingValue', {
              values: { age: metadata.ageRating },
            }),
      ],
      [
        'books',
        metadata?.totalBookCount == null
          ? ''
          : $_('catalog.metadata.display.bookCount', {
              values: { count: metadata.totalBookCount },
            }),
      ],
    ].filter(([, value]) => value !== ''),
  )

  const chipGroups = $derived(
    [
      ['genres', metadata?.genres ?? []],
      ['tags', metadata?.tags ?? []],
    ].filter(([, values]) => values.length > 0),
  )

  const links = $derived(metadata?.links ?? [])
</script>

{#if metadata}
  {#if metadata.summary}
    <p class="summary" data-testid="series-summary">{metadata.summary}</p>
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
