<script>
  /**
   * A cover image, or the absence of one.
   *
   * Artwork is produced during analysis: the first page for a comic or PDF, the cover
   * image declared in the OPF manifest for an EPUB that has one. An EPUB with no
   * declared cover, or a page analysis could not decode, is left without artwork
   * rather than having one synthesized some other way, so a `404` here is a real
   * state rather than a failure to retry. The placeholder is drawn locally instead of
   * asking the server to render an image on every request for something it has
   * already decided does not exist.
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
