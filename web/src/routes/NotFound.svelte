<script>
  import { _ } from '../lib/i18n.js'
</script>

<main>
  <p>{$_('routes.notFound')}</p>
  <a href="#/">{$_('routes.home')}</a>
</main>

<style>
  main {
    display: grid;
    min-height: 60dvh;
    gap: var(--space-3);
    place-content: center;
    text-align: center;
    color: var(--text-muted);
  }
  a {
    color: var(--accent-text);
    text-decoration: underline;
  }
</style>
