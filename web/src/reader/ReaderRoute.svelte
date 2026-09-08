<script>
  /**
   * Picks a reader by media kind.
   *
   * `COMIC` and `BOOK` both use the image reader: a PDF's pages are rendered by the
   * server, so from the client's side it is the same thing with a different page source.
   * `NOVEL` is an EPUB and needs its own reader — reflowable text, its own typography,
   * and locator-based progress rather than a page number alone.
   *
   * The item loaded here is passed to the chosen reader. Each reader can still load
   * itself when mounted directly, but route entry must not request the same item twice.
   */
  import { _ } from '../lib/i18n.js'
  import {
    isRetryableDeliveryFailure,
    readMediaItemReaderContext,
  } from '../lib/api/catalog.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Reader from './Reader.svelte'
  import EpubReader from './EpubReader.svelte'

  let { params } = $props()

  let kind = $state(null)
  let context = $state(null)
  let error = $state(null)
  let retryable = $state(false)
  let controller = null

  async function load(id) {
    controller?.abort()
    const requestController = new AbortController()
    controller = requestController
    context = null
    kind = null
    error = null
    retryable = false
    try {
      context = await readMediaItemReaderContext(id, { signal: requestController.signal })
      if (requestController.signal.aborted) return
      // `type` is the field the production Xoboro DTO actually serializes. Keep the
      // older aliases behind it for fixtures/older servers, but never let their absence
      // send a production NOVEL through the image reader.
      kind = context.item.type ?? context.item.mediaKind ?? context.item.kind ?? 'COMIC'
    } catch (caught) {
      if (requestController.signal.aborted) return
      error = caught
      retryable = isRetryableDeliveryFailure(caught)
    } finally {
      if (controller === requestController) controller = null
    }
  }

  $effect(() => {
    // Re-read when the identifier changes: walking to the next item in a series can
    // cross from a comic to a novel, and the wrong reader would render nothing.
    const id = params.id
    load(id)
    return () => controller?.abort()
  })
</script>

{#if error}
  <div class="failure">
    <ErrorNotice {error} onretry={retryable ? () => load(params.id) : null} />
  </div>
{:else if kind === 'NOVEL'}
  <EpubReader {params} initialItem={context.item} initialContext={context} />
{:else if kind}
  <Reader {params} initialContext={context} />
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
