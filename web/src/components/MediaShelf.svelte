<script>
  /**
   * A horizontal shelf for one named feed.
   *
   * Rendered only when it has something in it. An empty shelf with a heading tells a
   * reader that a feature exists and has nothing for them, which is worse than not
   * mentioning it — "on deck" is empty for anyone who has not started a series yet,
   * and that is the normal case on a fresh install.
   */
  import Cover from './Cover.svelte'
  import { artworkUrl } from '../lib/api/catalog.js'

  let {
    title,
    items,
    /** `'series'` or `'mediaItem'`, which decides both the artwork route and the link. */
    kind = 'series',
  } = $props()

  const href = (item) => (kind === 'series' ? `#/series/${item.id}` : `#/read/${item.id}`)
</script>

{#if items?.length}
  <section>
    <h2>{title}</h2>
    <ul>
      {#each items as item (item.id)}
        <li>
          <a href={href(item)}>
            <Cover src={artworkUrl(kind === 'series' ? 'series' : 'mediaItem', item.id)} />
            <span class="label">{item.title ?? item.name ?? item.id}</span>
            {#if kind === 'mediaItem' && item.seriesTitle}
              <span class="sub">{item.seriesTitle}</span>
            {/if}
          </a>
        </li>
      {/each}
    </ul>
  </section>
{/if}

<style>
  section {
    margin-bottom: var(--space-5);
  }
  h2 {
    margin: 0 0 var(--space-2);
    padding: 0 var(--gutter-right) 0 var(--gutter-left);
    font-size: var(--font-md);
  }
  ul {
    display: flex;
    margin: 0;
    padding: 0 var(--gutter-right) var(--space-1) var(--gutter-left);
    gap: var(--space-3);
    /* The shelf scrolls inside itself; the page body never scrolls sideways. */
    overflow-x: auto;
    list-style: none;
    scroll-snap-type: x proximity;
  }
  li {
    flex: 0 0 auto;
    width: 120px;
    scroll-snap-align: start;
  }
  .label {
    display: block;
    overflow: hidden;
    padding-top: var(--space-1);
    font-size: var(--font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sub {
    display: block;
    overflow: hidden;
    color: var(--text-muted);
    font-size: var(--font-xs);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
