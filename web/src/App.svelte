<script>
  /**
   * The session gate.
   *
   * Nothing behind this component ever renders without a session, so no screen
   * has to defend itself against being mounted unauthenticated.
   *
   * `UNKNOWN` is a real state, not a flash to skip. The cookie transport is
   * `HttpOnly`, so the client genuinely cannot know whether it is signed in until
   * the server answers — showing the login form during that window would ask
   * people who are already signed in to sign in again on every reload.
   */
  import { onMount } from 'svelte'
  import { _ } from './lib/i18n.js'
  import {
    SessionStatus,
    loadSession,
    readSetupState,
    session,
    watchForLostSession,
  } from './lib/session.js'
  import { eventHub } from './lib/eventHub.js'
  import ErrorNotice from './components/ErrorNotice.svelte'
  import SignIn from './routes/SignIn.svelte'
  import Setup from './routes/Setup.svelte'
  import AppShell from './AppShell.svelte'

  let claimed = $state(null)
  let error = $state(null)

  const status = $derived($session.status)

  onMount(() => {
    const stopWatching = watchForLostSession()

    const stopResync = eventHub.onResync(async (message) => {
      // `revoked` means this subscriber's authorization changed and the server
      // closed the stream. Re-reading the session is the only way to find out
      // whether they may still have one at all, so reconnecting before that
      // would be guessing.
      if (message.reason === 'revoked') await loadSession()
    })

    loadSession().catch((caught) => (error = caught))

    return () => {
      stopWatching()
      stopResync()
      eventHub.stop()
    }
  })

  // One stream for the whole application, opened when a session exists and closed
  // when it does not. Screens subscribe to the hub rather than opening their own:
  // the server allows four concurrent streams per user and evicts the oldest past
  // that, so per-screen connections would spend their time evicting each other.
  $effect(() => {
    if (status === SessionStatus.AUTHENTICATED) eventHub.start()
    else eventHub.stop()
  })

  // The setup state is only consulted while nobody is signed in. Asking for it
  // when a session already exists would be a request whose answer cannot change
  // anything on screen.
  $effect(() => {
    if (status !== SessionStatus.ANONYMOUS || claimed !== null) return
    readSetupState()
      .then((state) => (claimed = state.claimed))
      .catch((caught) => (error = caught))
  })
</script>

{#if status === SessionStatus.UNKNOWN}
  <p class="waiting" role="status" aria-live="polite">{$_('session.checking')}</p>
  <ErrorNotice {error} onretry={() => loadSession().catch((caught) => (error = caught))} />
{:else if status === SessionStatus.ANONYMOUS}
  {#if claimed === false}
    <Setup onclaimed={() => (claimed = true)} />
  {:else if claimed === true}
    <SignIn />
  {:else}
    <p class="waiting" role="status" aria-live="polite">{$_('common.loading')}</p>
    <ErrorNotice {error} />
  {/if}
{:else}
  <AppShell />
{/if}

<style>
  .waiting {
    display: grid;
    min-height: 100dvh;
    margin: 0;
    place-items: center;
    color: var(--text-muted);
  }
</style>
