<script>
  /**
   * A cover image, or the absence of one.
   *
   * Artwork is produced during scanning and there is no page-render fallback on the
   * server, so a `404` here is a real state rather than a failure to retry. The
   * placeholder is drawn locally instead of asking the server to synthesize an image
   * on every request for something it has already decided does not exist.
   *
   * The image is decorative: the title sits beside it as text, so announcing the same
   * words again from an `alt` would just repeat them.
   */
  let { src, ratio = '2 / 3' } = $props()

  let failed = $state(false)
</script>

<div class="cover" style={`aspect-ratio:${ratio}`}>
  {#if failed}
    <span class="placeholder" aria-hidden="true"></span>
  {:else}
    <img
      {src}
      alt=""
      role="presentation"
      loading="lazy"
      decoding="async"
      onerror={() => (failed = true)}
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
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .placeholder {
    display: block;
    width: 100%;
    height: 100%;
    background: linear-gradient(
      160deg,
      var(--surface-raised) 0%,
      var(--surface-selected) 100%
    );
  }
</style>
