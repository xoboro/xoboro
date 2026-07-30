# 0093 — OAuth2 account-linking policy and native configuration read-back

## Status

Accepted. Contains a breaking behaviour change; see Consequences.

## Context

`docs/feature-coverage.md` carried "Native configuration, production-provider acceptance, and
account-linking policy" as the remaining OAuth2/OIDC work.

The account-linking policy existed, but only as a line of code nobody had decided on.
`OAuth2LoginLifecycle.complete` ended with:

```kotlin
users.findByEmailIgnoreCaseOrNull(email)?.let { return it }
```

Any external identity asserting an email that matched a local account signed in **as that account**,
regardless of what the provider had actually verified. The exposure was configuration-dependent:

- OIDC **with** email verification required (the default): an unverified email never reached the
  lookup, so the behaviour was sound.
- OIDC with verification **not** required, or **any plain OAuth2 provider** — which has no verification
  claim at all: nothing stood between an asserted email and an existing account. An attacker able to
  register `admin@company.test` at a permissive provider took over the local administrator.

Those are precisely the configurations an operator reaches for while getting a provider working, which
is the worst possible time for the policy to be invisible.

## Decision

### The policy is named, with three values and a safe default

`OAuth2AccountLinking` decides whether an external identity may enter an existing local account:

| Value | Behaviour |
| --- | --- |
| `VERIFIED_EMAIL` | **Default.** Link only when the provider asserted it verified the email. A plain OAuth2 provider makes no such assertion, so under this policy it can never link — only create. |
| `EMAIL` | Link on an email match whatever the provider verified. Komga's behaviour, kept so a migrating deployment can choose it. |
| `NEVER` | Never link. A matching email is refused. |

`VERIFIED_EMAIL` is the default because it is the only value that is safe without knowing which
provider is configured. Defaulting to Komga's behaviour would have meant shipping the takeover above as
the out-of-the-box setting.

`NEVER` **refuses** rather than falling through to account creation. A duplicate account sharing an
email with an existing one is worse than either linking or refusing — it splits a person's library
across two accounts, silently, and leaves an administrator two rows that look like a bug.

The policy is configured by `XOBORO_OAUTH2_ACCOUNT_LINKING`. The name is deliberately Xoboro-prefixed:
Komga has no equivalent setting, and borrowing its `KOMGA_` prefix would imply a compatibility that
does not exist. An unrecognised value fails startup rather than falling back to a default — a
misspelled security setting must not resolve to something that merely looks like what was meant.

### Native configuration read-back is read-only, on purpose

`GET /api/xoboro/v1/authentication/oauth2` returns the configured providers (registration id and
display name) and the effective policy, to administrators.

Write access is **declined**, not deferred. Writing provider configuration through the API means
storing client secrets in the database, and the database is the one artifact that gets backed up,
copied to a laptop to debug a scan, and restored onto a staging host. Secrets belong in the process
environment, which is none of those things. Registration stays in environment variables.

What was actually missing was not the ability to *change* the configuration — an operator who can set
environment variables can already do that — but the ability to *see what a running deployment
resolved*. Until now that required reading the environment on the host, which is exactly what an
administrator with a browser does not have.

Only names and identifiers cross the wire. No client id, no client secret, no endpoint URI: those
would turn a configuration display into a credential disclosure, and anyone who needs them already has
the environment that holds them.

## Consequences

- **Breaking, deliberately.** A deployment using a plain OAuth2 provider, or OIDC with
  `KOMGA_OIDC_EMAIL_VERIFICATION=false`, that relied on email-match linking will now refuse those
  logins with `account_linking_requires_verified_email`. Setting
  `XOBORO_OAUTH2_ACCOUNT_LINKING=EMAIL` restores the previous behaviour. OIDC deployments on the
  default settings are unaffected, because verification was already required before the lookup.
- Two new error codes, `account_linking_disabled` and `account_linking_requires_verified_email`. They
  are string codes rather than `ERR_1xxx` numbers because they have no Komga counterpart to match, and
  the surrounding non-Komga codes (`invalid_state`, `unknown_registration`) already read this way.
- The refusal deliberately does not distinguish "this email belongs to a local account" from other
  failures in its message to the browser; the code is precise for an administrator reading logs.
- Verified by mutation: making `VERIFIED_EMAIL` permit everything — which *is* the pre-decision
  behaviour — fails `refuses to enter an existing account on an unverified assertion`, and making
  `NEVER` permit linking fails `NEVER linking refuses even a verified assertion`.
- Production-provider acceptance against a real Google/Keycloak/Authentik deployment remains open. It
  needs live credentials, so it cannot be covered by a synthetic test, and the coverage row still says
  so.
