<script>
  /**
   * The two ways into a series: carry on, or start over.
   *
   * "Continue" is shown only when it would go somewhere else. For a series the reader
   * has never opened, the item to resume *is* the first one, and offering both buttons
   * pointing at the same chapter is two decisions for one outcome.
   *
   * Rendered as links, not buttons: they navigate, so a reader can open a chapter in a
   * new tab and the browser's own affordances work. Their appearance is the difference
   * between them — continue is the filled one, because it is what a returning reader
   * wants and only one of the two can be primary.
   */
  import { BookOpen, Play } from '@lucide/svelte'
  import { _ } from '../lib/i18n.js'

  let {
    /** The earliest item in the series, or null when it has none. */
    first,
    /** The item a reader would resume, or null when they have not started. */
    resume,
  } = $props()

  const showResume = $derived(Boolean(resume) && resume.id !== first?.id)
  const resumeLabel = $derived(
    resume?.title ?? resume?.name ?? '',
  )
</script>

{#if first}
  <div class="actions">
    {#if showResume}
      <a class="primary" href={`#/read/${resume.id}`} data-testid="series-continue">
        <Play size={16} aria-hidden="true" />
        <span class="text">
          {$_('reader.continue')}
          {#if resumeLabel}<span class="where">{resumeLabel}</span>{/if}
        </span>
      </a>
    {/if}
    <a class="secondary" href={`#/read/${first.id}`} data-testid="series-from-start">
      <BookOpen size={16} aria-hidden="true" />
      <span class="text">{$_('reader.fromStart')}</span>
    </a>
  </div>
{/if}

<style>
  .actions {
    display: flex;
    gap: var(--space-2);
    padding: 0 var(--gutter-right) var(--space-4) var(--gutter-left);
  }
  a {
    display: flex;
    min-height: var(--touch-target);
    flex: 1 1 0;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    padding: 0 var(--space-3);
    border: 1px solid transparent;
    border-radius: var(--radius);
    font-size: var(--font-sm);
  }
  .primary {
    background: var(--accent);
    color: var(--accent-contrast);
    font-weight: 600;
  }
  .secondary {
    border-color: var(--line-strong);
    background: var(--surface-control);
    color: var(--text);
  }
  .text {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* The chapter name is supporting detail, so it never competes with the verb and
     never grows the button: it is the part that gets clipped on a narrow screen. */
  .where {
    margin-left: var(--space-2);
    font-weight: 400;
    opacity: 0.75;
  }
</style>
