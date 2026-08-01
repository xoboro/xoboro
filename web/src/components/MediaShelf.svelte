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
    /*
     * Snapping has to be told about the gutter, or it eats it. `scroll-snap-align: start`
     * below aligns a card's start edge with the *scrollport's* start edge, which is inside
     * the padding — so a shelf that snapped came to rest at `scrollLeft: 16` with its first
     * card flush against the window edge and visibly clipped, while the heading above it
     * kept its gutter. `proximity` made it intermittent: on a real library two shelves
     * rested at 0 and a third at 16, so it looked like a rendering glitch rather than a rule.
     *
     * Not covered by the suite, and cannot be: jsdom has no layout, so `scrollLeft` is
     * always 0 there and any assertion about this would pass with the bug present. It was
     * found and is verified by measuring `scrollLeft` in a real browser — see the
     * "Checking the web UI against a real server" section of `docs/testing.md`.
     */
    scroll-padding-inline: var(--gutter-left) var(--gutter-right);
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
