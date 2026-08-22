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
  import { onDestroy, onMount, untrack } from 'svelte'

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
  let shimmerTimer = null
  let coverElement
  let visible = $state(false)
  let shimmering = $state(false)
  let retrying = $state(false)
  let disposed = false

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
    stopShimmer()
    ready = false
    failed = false
    attempt = 0
    retrying = false
    untrack(scheduleShimmer)
  })

  onMount(() => {
    if (typeof IntersectionObserver === 'undefined') return

    const observer = new IntersectionObserver(([entry]) => {
      if (disposed) return
      visible = entry?.isIntersecting ?? false
      scheduleShimmer()
    })
    observer.observe(coverElement)

    return () => observer.disconnect()
  })

  onDestroy(() => {
    disposed = true
    clearTimeout(retryTimer)
    clearTimeout(shimmerTimer)
  })

  function stopShimmer() {
    clearTimeout(shimmerTimer)
    shimmerTimer = null
    shimmering = false
  }

  function scheduleShimmer() {
    if (disposed) return
    stopShimmer()
    if (!visible || ready || failed || retrying) return

    shimmerTimer = setTimeout(() => {
      if (visible && !ready && !failed && !retrying) shimmering = true
    }, 250)
  }

  function loaded() {
    stopShimmer()
    retrying = false
    ready = true
  }

  function missing() {
    stopShimmer()
    const delay = retryDelays[attempt]
    if (delay === undefined) {
      failed = true
      return
    }
    retrying = true
    const next = attempt + 1
    clearTimeout(retryTimer)
    retryTimer = setTimeout(() => (attempt = next), delay)
  }

</script>

<div class="cover" style={`aspect-ratio:${ratio}`} bind:this={coverElement}>
  {#if !ready}
    <span class="placeholder" class:shimmering class:permanent={failed} data-testid="cover-placeholder" aria-hidden="true"></span>
  {/if}
  {#if !failed}
    <img
      src={url}
      alt=""
      class:ready
      role="presentation"
      loading="lazy"
      decoding="async"
      onload={loaded}
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
     * Where the sheen sits when it is not moving at all.
     *
     * `forwards` only covers the path that runs the animation to its end. `.permanent` and
     * the reduced-motion rule below never start one, so without this they would rest at the
     * initial `0% 0%` — the sheen parked mid-sweep inside the box, frozen, on exactly the
     * covers that are never going to change. Declaring the `to` position keeps all three
     * resting states identical.
     */
    background-position: -220% 0, 0 0;
  }
  /* Motion is feedback for a wait the reader can actually see. The component adds this
     class only after a visible cover has remained unresolved for 250ms; lazy off-screen
     covers and retrying 404s keep the same placeholder without spending animation work. */
  .placeholder.shimmering {
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
    .placeholder.shimmering {
      animation: none;
    }
    img {
      transition: none;
    }
  }
</style>
