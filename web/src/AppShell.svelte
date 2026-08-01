<script>
  /**
   * Routing for a signed-in session.
   *
   * The administrator console is a **dynamically imported** route. That is what
   * makes "one application, two shells" cost the reader nothing: someone who never
   * opens the console never downloads it, and the build shows the admin code in its
   * own chunk rather than in the entry bundle.
   *
   * Hash routing, so a deep link needs no server rewrite rule — which is the usual
   * way an SPA route quietly shadows an API route.
   */
  import Router from 'svelte-spa-router'
  import { wrap } from 'svelte-spa-router/wrap'
  import Home from './reader/Home.svelte'
  import Search from './reader/Search.svelte'
  import SeriesScreen from './reader/SeriesScreen.svelte'
  import ReaderRoute from './reader/ReaderRoute.svelte'
  import Groupings from './catalog/Groupings.svelte'
  import NotFound from './routes/NotFound.svelte'

  const routes = {
    '/': Home,
    // Reader-side and eagerly loaded, unlike the console: search is part of browsing
    // the library, so it is on the path a reader takes rather than off it.
    '/search': Search,
    '/series/:id': SeriesScreen,
    '/read/:id': ReaderRoute,
    // Reader-side, not console: reading a collection is open to any authenticated
    // caller, and only its mutations are administrator-only.
    '/collections': wrap({ component: Groupings, props: { kind: 'collection' } }),
    '/read-lists': wrap({ component: Groupings, props: { kind: 'readList' } }),
    // The wildcard is required: the console does its own nested routing, so it has
    // to keep matching for every path beneath it.
    '/admin/*': wrap({ asyncComponent: () => import('./admin/AdminShell.svelte') }),
    '/admin': wrap({ asyncComponent: () => import('./admin/AdminShell.svelte') }),
    '*': NotFound,
  }
</script>

<Router {routes} />
