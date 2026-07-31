<script>
  /**
   * Picks a reader by media kind.
   *
   * `COMIC` and `BOOK` both use the image reader: a PDF's pages are rendered by the
   * server, so from the client's side it is the same thing with a different page source.
   * `NOVEL` is an EPUB and needs its own reader — reflowable text, its own typography,
   * and locator-based progress rather than a page number alone.
   *
   * This costs one small extra request for the item, which the chosen reader then loads
   * again with its manifest. Threading the loaded item through props would save it, at
   * the price of two readers that can no longer load themselves; a single JSON GET is
   * the cheaper trade.
   */
  import { _ } from '../lib/i18n.js'
  import { isRetryableDeliveryFailure, readMediaItem } from '../lib/api/catalog.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Reader from './Reader.svelte'
  import EpubReader from './EpubReader.svelte'

  let { params } = $props()

  let kind = $state(null)
  let error = $state(null)
  let retryable = $state(false)

  async function load() {
    error = null
    retryable = false
    try {
      const item = await readMediaItem(params.id)
      kind = item.mediaKind ?? item.kind ?? 'COMIC'
    } catch (caught) {
      error = caught
      retryable = isRetryableDeliveryFailure(caught)
    }
  }

  $effect(() => {
    // Re-read when the identifier changes: walking to the next item in a series can
    // cross from a comic to a novel, and the wrong reader would render nothing.
    params.id
    load()
  })
</script>

{#if error}
  <div class="failure">
    <ErrorNotice {error} onretry={retryable ? load : null} />
  </div>
{:else if kind === 'NOVEL'}
  <EpubReader {params} />
{:else if kind}
  <Reader {params} />
{:else}
  <p class="waiting" role="status">{$_('common.loading')}</p>
{/if}

<style>
  .failure {
    padding: var(--space-6) var(--gutter-right) var(--space-6) var(--gutter-left);
  }
  .waiting {
    display: grid;
    min-height: 60dvh;
    margin: 0;
    place-items: center;
    color: var(--text-muted);
  }
</style>
