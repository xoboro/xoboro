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

  onDestroy(() => clearTimeout(retryTimer))
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
  .placeholder {
    position: absolute;
    inset: 0;
    display: block;
    background: linear-gradient(
      110deg,
      var(--surface-raised) 25%,
      var(--surface-selected) 42%,
      var(--surface-raised) 60%
    );
    background-size: 220% 100%;
    /*
     * Bounded, not `infinite`. `loading="lazy"` means an off-screen image fires neither
     * `load` nor `error`, so its placeholder is the one that never resolves - and a home
     * grid holds a hundred of them. An unbounded shimmer there is a hundred compositor
     * animations running for a page nobody has scrolled to. Six iterations is about eleven
     * seconds, well past any cover that is actually coming, and it settles into the same
     * still gradient afterwards.
     */
    animation: cover-shimmer 1.8s ease-in-out 6;
    animation-fill-mode: forwards;
  }
  /* Nothing is on its way any more, so the movement would be saying something untrue. */
  .placeholder.permanent {
    animation: none;
    background: linear-gradient(
      160deg,
      var(--surface-raised) 0%,
      var(--surface-selected) 100%
    );
  }
  @keyframes cover-shimmer {
    to {
      background-position: -220% 0;
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
