/** Share the router's imports so preparing the next screen warms the same module. */
export function loadSeriesScreen() {
  return import('../reader/SeriesScreen.svelte')
}

export function loadReaderScreen() {
  return import('../reader/ReaderRoute.svelte')
}
