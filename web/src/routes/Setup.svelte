<script>
  /**
   * Claims an unconfigured server, creating the first administrator.
   *
   * A `409 server_already_claimed` is not an error to display: it means somebody
   * else finished setup while this form was open, and the only sensible next step
   * is the sign-in screen. The confirmation field exists because this password is
   * typed once, with no account to recover it from if it was mistyped.
   */
  import { _ } from '../lib/i18n.js'
  import { Treatment } from '../lib/errors.js'
  import { claimServer } from '../lib/session.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Logo from '../components/Logo.svelte'

  let { onclaimed } = $props()

  let email = $state('')
  let password = $state('')
  let confirmation = $state('')
  let submitting = $state(false)
  let error = $state(null)

  const mismatch = $derived(confirmation.length > 0 && password !== confirmation)
  const ready = $derived(
    email.trim().length > 0 && password.length > 0 && password === confirmation,
  )

  async function submit(event) {
    event.preventDefault()
    if (submitting || !ready) return
    submitting = true
    error = null
    try {
      await claimServer(email, password)
    } catch (caught) {
      if (caught.treatment === Treatment.SETUP_DONE) {
        onclaimed()
        return
      }
      error = caught
    } finally {
      submitting = false
    }
  }
</script>

<main>
  <form onsubmit={submit} novalidate>
    <!-- Labelled for the same reason as the sign-in screen: the heading names the
         task, not the product. -->
    <div class="brand"><Logo size={56} label="Xoboro" /></div>
    <h1>{$_('session.setupTitle')}</h1>
    <p class="hint">{$_('session.setupHint')}</p>

    <label for="setup-email">{$_('session.email')}</label>
    <input
      id="setup-email"
      type="email"
      autocomplete="username"
      inputmode="email"
      bind:value={email}
      required
    />

    <label for="setup-password">{$_('session.password')}</label>
    <input
      id="setup-password"
      type="password"
      autocomplete="new-password"
      bind:value={password}
      required
    />

    <label for="setup-confirmation">{$_('session.passwordConfirm')}</label>
    <input
      id="setup-confirmation"
      type="password"
      autocomplete="new-password"
      aria-invalid={mismatch ? 'true' : undefined}
      aria-describedby={mismatch ? 'setup-mismatch' : undefined}
      bind:value={confirmation}
      required
    />
    {#if mismatch}
      <p class="mismatch" id="setup-mismatch" role="alert">
        {$_('session.passwordMismatch')}
      </p>
    {/if}

    <ErrorNotice {error} />

    <button type="submit" disabled={submitting || !ready}>
      {submitting ? $_('session.setupSubmitting') : $_('session.setupSubmit')}
    </button>
  </form>
</main>

<style>
  main {
    display: grid;
    min-height: 100dvh;
    place-items: center;
    padding: var(--space-5) var(--gutter-right) var(--space-5) var(--gutter-left);
  }
  form {
    display: flex;
    width: min(360px, 100%);
    flex-direction: column;
    gap: var(--space-2);
  }
  .brand {
    display: flex;
    justify-content: center;
    margin: 0 0 var(--space-4);
  }
  h1 {
    margin: 0;
    font-size: var(--font-xl);
  }
  .hint {
    margin: 0 0 var(--space-3);
    color: var(--text-muted);
    font-size: var(--font-sm);
  }
  label {
    color: var(--text-secondary);
    font-size: var(--font-sm);
    font-weight: 600;
  }
  input {
    min-height: var(--touch-target);
    padding: 0 var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-raised);
    color: var(--text);
  }
  input[aria-invalid='true'] {
    border-color: var(--danger);
  }
  .mismatch {
    margin: 0;
    color: var(--danger);
    font-size: var(--font-sm);
  }
  button {
    min-height: var(--touch-target);
    margin-top: var(--space-3);
    border: 0;
    border-radius: var(--radius);
    background: var(--accent);
    color: var(--accent-contrast);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
