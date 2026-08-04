<script>
  /**
   * The Xoboro mark.
   *
   * The artwork as supplied, at whatever size the caller asks for. It is the same file
   * the browser tab loads, so there is one copy of the brand in the repository and no
   * chance of the two drifting.
   *
   * Imported rather than referenced by path so Vite rewrites the URL against
   * `base: './'`. A literal "/xoboro.svg" would 404 under a context-path deployment,
   * which is the same reason vite.config.js pins that base.
   *
   * Decorative by default, because the common placement is beside a heading that
   * already names the screen. Where the mark is the only thing carrying the product
   * name — the headings read "내 서재" and "로그인", not "Xoboro" — pass `label`, or a
   * screen reader gets no brand at all.
   */
  import mark from '../assets/xoboro.svg'

  let {
    /** Rendered edge length in pixels. The source is square. */
    size = 28,
    /** Accessible name. Empty means decorative, which is the common case. */
    label = '',
  } = $props()
</script>

<img
  class="mark"
  src={mark}
  width={size}
  height={size}
  alt={label}
  aria-hidden={label ? undefined : 'true'}
  draggable="false"
/>

<style>
  .mark {
    display: block;
    flex: 0 0 auto;
    /* The source is square and already carries the brand background to its own
       edges, so it is clipped rather than padded. */
    border-radius: var(--radius-sm);
    object-fit: contain;
    user-select: none;
  }
</style>
