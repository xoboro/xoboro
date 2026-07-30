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
  import { StreamStatus, createEventHub } from './lib/sse.js'
  import ErrorNotice from './components/ErrorNotice.svelte'
  import SignIn from './routes/SignIn.svelte'
  import Setup from './routes/Setup.svelte'
  import SignedIn from './routes/SignedIn.svelte'

  const hub = createEventHub()

  let claimed = $state(null)
  let error = $state(null)
  let streamStatus = $state(StreamStatus.IDLE)
  let resyncReason = $state(null)

  const status = $derived($session.status)
  const user = $derived($session.user)

  onMount(() => {
    const stopWatching = watchForLostSession()
    const stopStatus = hub.status.subscribe((value) => (streamStatus = value))

    const stopResync = hub.onResync(async (message) => {
      resyncReason = message.reason
      // `revoked` means this subscriber's authorization changed and the server
      // closed the stream. Re-reading the session is the only way to find out
      // whether they may still have one at all, so reconnecting before that
      // would be guessing.
      if (message.reason === 'revoked') await loadSession()
    })

    loadSession().catch((caught) => (error = caught))

    return () => {
      stopWatching()
      stopStatus()
      stopResync()
      hub.stop()
    }
  })

  // One stream for the whole application, opened when a session exists and closed
  // when it does not. Screens subscribe to the hub rather than opening their own:
  // the server allows four concurrent streams per user and evicts the oldest past
  // that, so per-screen connections would spend their time evicting each other.
  $effect(() => {
    if (status === SessionStatus.AUTHENTICATED) hub.start()
    else hub.stop()
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
  <SignedIn {user} {streamStatus} {resyncReason} />
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
