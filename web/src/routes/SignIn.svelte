<script>
  /**
   * The sign-in gate.
   *
   * Password attempts are throttled server-side to ten per minute per verified
   * client IP, and rejected attempts consume the same budget as accepted ones. A
   * `429` therefore carries a `Retry-After`, and submit stays disabled until it
   * has elapsed — a form that lets someone keep hammering a throttled endpoint
   * only spends their remaining budget for them.
   */
  import { onDestroy } from 'svelte'
  import { _ } from '../lib/i18n.js'
  import { Treatment } from '../lib/errors.js'
  import { signIn } from '../lib/session.js'
  import ErrorNotice from '../components/ErrorNotice.svelte'
  import Logo from '../components/Logo.svelte'

  let email = $state('')
  let password = $state('')
  let submitting = $state(false)
  let error = $state(null)
  let waitSeconds = $state(0)
  let ticker = null

  const blocked = $derived(submitting || waitSeconds > 0)
  const passwordError = $derived(error?.field === 'password' ? error : null)

  function countDown(seconds) {
    clearInterval(ticker)
    waitSeconds = seconds
    ticker = setInterval(() => {
      waitSeconds -= 1
      if (waitSeconds <= 0) clearInterval(ticker)
    }, 1000)
  }

  async function submit(event) {
    event.preventDefault()
    if (blocked) return
    submitting = true
    error = null
    try {
      await signIn(email, password)
    } catch (caught) {
      error = caught
      if (caught.treatment === Treatment.THROTTLED && caught.retryAfterSeconds > 0) {
        countDown(caught.retryAfterSeconds)
      }
    } finally {
      submitting = false
    }
  }

  onDestroy(() => clearInterval(ticker))
</script>

<main>
  <form onsubmit={submit} novalidate>
    <!-- The mark is labelled here: the heading reads "로그인", so nothing else on
         this screen tells a screen reader which server it is signing in to. -->
    <div class="brand"><Logo size={56} label="Xoboro" /></div>
    <h1>{$_('session.signInTitle')}</h1>

    <label for="signin-email">{$_('session.email')}</label>
    <!-- svelte-ignore a11y_autofocus -->
    <input
      id="signin-email"
      type="email"
      autocomplete="username"
      inputmode="email"
      bind:value={email}
      required
    />

    <label for="signin-password">{$_('session.password')}</label>
    <input
      id="signin-password"
      type="password"
      autocomplete="current-password"
      aria-invalid={passwordError ? 'true' : undefined}
      aria-describedby={passwordError ? 'signin-error' : undefined}
      bind:value={password}
      required
    />

    {#if error}
      <div id="signin-error">
        <ErrorNotice {error} />
      </div>
    {/if}

    <button type="submit" disabled={blocked}>
      {#if submitting}
        {$_('session.signingIn')}
      {:else if waitSeconds > 0}
        {$_('session.throttled', { values: { seconds: waitSeconds } })}
      {:else}
        {$_('session.signIn')}
      {/if}
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
    margin: 0 0 var(--space-4);
    font-size: var(--font-xl);
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
