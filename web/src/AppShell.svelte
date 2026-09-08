<script>
  /**
   * Routing for a signed-in session.
   *
   * Every screen is dynamically imported. A reader downloads the current screen,
   * not search, series, both readers, groupings, and the console before the home route
   * can render.
   *
   * Hash routing, so a deep link needs no server rewrite rule — which is the usual
   * way an SPA route quietly shadows an API route.
   */
  import Router from 'svelte-spa-router'
  import { wrap } from 'svelte-spa-router/wrap'

  const routes = {
    '/': wrap({ asyncComponent: () => import('./reader/Home.svelte') }),
    '/search': wrap({ asyncComponent: () => import('./reader/Search.svelte') }),
    '/series/:id': wrap({ asyncComponent: () => import('./reader/SeriesScreen.svelte') }),
    '/read/:id': wrap({ asyncComponent: () => import('./reader/ReaderRoute.svelte') }),
    // Reader-side, not console: reading a collection is open to any authenticated
    // caller, and only its mutations are administrator-only.
    '/collections': wrap({
      asyncComponent: () => import('./catalog/Groupings.svelte'),
      props: { kind: 'collection' },
    }),
    '/read-lists': wrap({
      asyncComponent: () => import('./catalog/Groupings.svelte'),
      props: { kind: 'readList' },
    }),
    // The wildcard is required: the console does its own nested routing, so it has
    // to keep matching for every path beneath it.
    '/admin/*': wrap({ asyncComponent: () => import('./admin/AdminShell.svelte') }),
    '/admin': wrap({ asyncComponent: () => import('./admin/AdminShell.svelte') }),
    '*': wrap({ asyncComponent: () => import('./routes/NotFound.svelte') }),
  }
</script>

<Router {routes} />
