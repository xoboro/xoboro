<script>
  /**
   * Console navigation.
   *
   * Only the screens that exist are listed. A navigation entry for an unbuilt screen
   * is a promise the console does not keep, and it costs an operator a click to find
   * that out.
   */
  // svelte-spa-router 5 replaced the `location` store with a rune-backed `router`
  // object. The old import type-checks and even passes under Vitest's resolution,
  // but fails the production build with MISSING_EXPORT — so the build is the thing
  // that catches it, not the tests.
  import { router } from 'svelte-spa-router'
  import {
    Activity,
    Archive,
    Copy,
    History,
    KeyRound,
    Library,
    ListChecks,
    Settings,
    ShieldCheck,
    Trash2,
    Users,
  } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'

  const items = [
    { path: '/admin', key: 'admin.nav.overview', icon: Activity },
    { path: '/admin/libraries', key: 'admin.nav.libraries', icon: Library },
    { path: '/admin/trash', key: 'admin.nav.trash', icon: Trash2 },
    { path: '/admin/tasks', key: 'admin.nav.tasks', icon: ListChecks },
    { path: '/admin/users', key: 'admin.nav.users', icon: Users },
    { path: '/admin/api-keys', key: 'admin.nav.apiKeys', icon: KeyRound },
    { path: '/admin/security', key: 'admin.nav.security', icon: ShieldCheck },
    { path: '/admin/duplicates', key: 'admin.nav.duplicates', icon: Copy },
    { path: '/admin/history', key: 'admin.nav.history', icon: History },
    { path: '/admin/backups', key: 'admin.nav.backups', icon: Archive },
    { path: '/admin/settings', key: 'admin.nav.settings', icon: Settings },
  ]
</script>

<nav aria-label={$_('admin.title')}>
  <p class="title">{$_('admin.title')}</p>
  <ul>
    {#each items as item (item.path)}
      {@const current = router.location === item.path}
      <li>
        <!-- aria-current is what tells a screen reader which screen this is, and it
             is not implied by the visual highlight. -->
        <a href={`#${item.path}`} aria-current={current ? 'page' : undefined} class:current>
          <item.icon size={17} aria-hidden="true" />
          <span>{$_(item.key)}</span>
        </a>
      </li>
    {/each}
  </ul>
</nav>

<style>
  nav {
    padding: max(var(--space-3), calc(var(--inset-top) + var(--space-2)))
      var(--space-3) var(--space-3) max(var(--space-3), var(--inset-left));
    border-bottom: 1px solid var(--line-subtle);
    background: var(--surface-inset);
  }
  .title {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--font-xs);
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  ul {
    display: flex;
    margin: 0;
    padding: 0;
    gap: var(--space-1);
    overflow-x: auto;
    list-style: none;
  }
  a {
    display: flex;
    min-height: var(--touch-target);
    align-items: center;
    gap: var(--space-2);
    padding: 0 var(--space-3);
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    font-size: var(--font-sm);
    white-space: nowrap;
  }
  a.current {
    background: var(--surface-selected);
    color: var(--text-strong);
    font-weight: 600;
  }

  @media (min-width: 1024px) {
    nav {
      border-right: 1px solid var(--line-subtle);
      border-bottom: 0;
    }
    ul {
      flex-direction: column;
      overflow-x: visible;
    }
  }
</style>
