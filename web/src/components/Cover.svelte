<script>
  /**
   * A cover image, or the absence of one, or the wait in between.
   *
   * Artwork is produced during analysis: the first page for a comic or PDF, the cover
   * image declared in the OPF manifest for an EPUB that has one. An EPUB with no
   * declared cover, or a page analysis could not decode, is left without artwork rather
   * than having one synthesized some other way — so a `404` here is a real state and the
   * placeholder that follows it is permanent, drawn locally instead of asking the server
   * to render an image for something it has already decided does not exist.
   *
   * What this used to miss is the third state. The image was rendered and nothing else,
   * so until it decoded the box was empty, and a grid on a first visit — or during a
   * scan, when artwork is still being produced — was a page of blank rectangles. That
   * reads as a library with no covers rather than as covers on their way. The
   * placeholder now shows from the start and the image fades in over it.
   *
   * The image is decorative: the title sits beside it as text, so announcing the same
   * words again from an `alt` would just repeat them.
   */
  import { onDestroy } from 'svelte'

  let {
    src,
    ratio = '2 / 3',
    /**
     * How long to wait before each retry.
     *
     * Bounded on purpose. A missing cover is usually a decision — an EPUB that declares
     * none — and retrying that forever would ask the server to keep answering `404` for
     * something it has already settled. But it is *transient* while a scan is still
     * producing artwork, and a reader watching a library fill up should not have to
     * reload the page to see it. Three tries over roughly half a minute covers the
     * second case without pestering the server about the first.
     */
    retryDelays = [2000, 8000, 20000],
  } = $props()

  let ready = $state(false)
  let failed = $state(false)
  /** Bumped per retry and appended to the URL, because the browser caches the 404. */
  let attempt = $state(0)
  let retryTimer = null

  const url = $derived(
    attempt === 0 ? src : `${src}${src.includes('?') ? '&' : '?'}retry=${attempt}`,
  )

  /**
   * A new source is a new wait.
   *
   * A grid reuses one component instance as it pages, so leaving `ready` set would keep
   * the previous series' cover on screen — visibly wrong — until the next one decoded.
   */
  $effect(() => {
    src
    clearTimeout(retryTimer)
    ready = false
    failed = false
    attempt = 0
  })

  onDestroy(() => clearTimeout(retryTimer))

  function missing() {
    const delay = retryDelays[attempt]
    if (delay === undefined) {
      failed = true
      return
    }
    const next = attempt + 1
    clearTimeout(retryTimer)
    retryTimer = setTimeout(() => (attempt = next), delay)
  }

</script>

<div class="cover" style={`aspect-ratio:${ratio}`}>
  {#if !ready}
    <span class="placeholder" class:permanent={failed} data-testid="cover-placeholder" aria-hidden="true"></span>
  {/if}
  {#if !failed}
    <img
      src={url}
      alt=""
      class:ready
      role="presentation"
      loading="lazy"
      decoding="async"
      onload={() => (ready = true)}
      onerror={missing}
    />
  {/if}
</div>

<style>
  .cover {
    position: relative;
    width: 100%;
    overflow: hidden;
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
  }
  img {
    position: absolute;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0;
    transition: opacity 180ms ease;
  }
  img.ready {
    opacity: 1;
  }
  /*
   * Two layers: a travelling highlight, and the fill it travels over.
   *
   * The highlight is translucent rather than opaque, and that is the whole point. An opaque
   * travelling gradient decides the resting appearance by wherever it happens to stop — and
   * `background-position: -220%` computes back to about 0%, so it stopped showing its own
   * first stop, which is `--surface-raised`, which is `.cover`'s own background. Eleven
   * seconds of motion ended in the empty box this component exists to stop showing.
   * Measured, not reasoned: the rested centre pixel differed from the cover's own
   * background by 2 of 255 per channel — invisible. With the fill underneath it differs by
   * 9 at the centre and 17 at the corner.
   *
   * With the fill underneath and the highlight see-through, where the animation stops no
   * longer decides anything: the placeholder always reads as the same distinct fill a cover
   * that will never arrive settles on.
   */
  .placeholder {
    position: absolute;
    inset: 0;
    display: block;
    background-image:
      linear-gradient(
        110deg,
        transparent 25%,
        var(--surface-sheen) 42%,
        transparent 60%
      ),
      linear-gradient(160deg, var(--surface-raised) 0%, var(--surface-selected) 100%);
    background-size: 220% 100%, 100% 100%;
    background-repeat: no-repeat, no-repeat;
    /*
     * Bounded, not `infinite`. `loading="lazy"` means an off-screen image fires neither
     * `load` nor `error`, so its placeholder is the one that never resolves — and a home
     * grid holds a hundred of them. An unbounded shimmer there is a hundred compositor
     * animations running for a page nobody has scrolled to.
     *
     * Seventeen iterations rather than six, because six ends at 10.8s while `retryDelays`
     * keeps asking until 30s: a cover still on its way and one that will never arrive
     * looked identical for the nineteen seconds in between. This covers the whole window.
     *
     * `forwards` is required, not decoration. Without it the rest state is the *specified*
     * position, `0%`, which parks the sheen inside the box on a mid-sweep frame — the
     * highlight pops back in at the edge and freezes there, which reads as a rendering
     * fault. At the `to` frame it sits entirely outside.
     */
    animation: cover-shimmer 1.8s ease-in-out 17;
    animation-fill-mode: forwards;
  }
  /* Nothing is on its way any more, so the movement would be saying something untrue. The
     fill is already the one underneath the highlight, so stopping changes only the motion. */
  .placeholder.permanent {
    animation: none;
  }
  @keyframes cover-shimmer {
    from {
      background-position: 220% 0, 0 0;
    }
    to {
      background-position: -220% 0, 0 0;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .placeholder {
      animation: none;
    }
    img {
      transition: none;
    }
  }
</style>
