# 0102 — Provisioning the initial administrator

## Status

Accepted.

## Context

Claiming a Xoboro server required an interactive `POST /setup`. A deployment brought up from a Compose
file or a Kubernetes manifest has nobody to make that call, so it either **stays unclaimed** — running,
reachable, and waiting for whoever finds it first to become its administrator — or somebody scripts a
`curl` against it and now the credentials live in a shell script instead.

The coverage row asked for "an automated initial-user option". The whole difficulty is that the option
handles a password.

## Decision

### The file form is preferred and wins when both are given

`XOBORO_INITIAL_ADMIN_PASSWORD_FILE` takes precedence over `XOBORO_INITIAL_ADMIN_PASSWORD`.

A password in the process environment is readable from `/proc/<pid>/environ`, appears in
`docker inspect`, and gets committed in the Compose file that sets it. A file can carry restrictive
permissions and is what Docker and Kubernetes secrets already mount.

The environment form still exists, because it is what people reach for first and refusing it would mean
they script a `curl` instead — which is worse. But the ordering states which one is right.

Only the trailing newline is stripped, not surrounding whitespace. A password may legitimately contain
leading or inner spaces, and trimming would **silently change the secret** rather than reject a
malformed file.

### Half-configuration fails startup

An email with no password, or a password with no email, is an error.

The tempting alternative — skip provisioning and come up normally — is the dangerous one. An operator
who set one half expected provisioning to happen; a server that quietly came up **unclaimed** instead is
reachable by whoever finds it first, which is the exact outcome this feature exists to prevent. Failing
loudly at configuration time, before the port is bound, is the only safe reading of a half-set
configuration.

A password file that is missing or empty is likewise an error, and for the same reason: **a secret mount
that failed is not the same as "no secret configured"**, and treating them alike turns a broken
deployment into an open one.

### The claim runs only while the server is unclaimed

This is the security decision, not a convenience.

The variables live in the manifest, so they are still set on every restart. If startup re-claimed — or
reset the password to match the configuration — then anyone able to edit the environment could take the
administrator account over by restarting the server. A provisioning convenience would have become a back
door.

So the claim goes through `claimInitialAdministrator`, whose `claimIfEmpty` does the check atomically,
and `ServerAlreadyClaimedException` is the **expected** outcome on every restart after the first. It is
logged at `FINE`, not as an error.

A test asserts the whole shape: provision, change the password, restart with the variables still set,
and confirm the chosen password works while the provisioning password no longer does. A mutation that
resets the password on each start fails it.

### The password never reaches a log

`InitialAdministrator.toString` redacts it, and a test asserts that the rendering of the whole
`ServerConfig` does not contain it. `ServerConfig` is exactly the kind of object that ends up in a log
line or an exception message, so the redaction belongs on the type rather than at each call site.

The success log line carries the **email only**. That line goes to the same log an operator pastes into
an issue.

## Consequences

- A container deployment can be fully provisioned from a manifest, with the secret in a mounted file.
- Startup fails on a half-set or broken configuration rather than coming up unclaimed.
- Restarting is safe and idempotent; changing the password after provisioning is the documented next
  step, and the provisioning value stops working the moment it happens.
- **Named discovery feeds are not part of this change**, and the `Filters, sorts, facets…` row still
  lists them. `onDeck` and `keepReading` already exist as query parameters on the media-item listing, so
  a client can build every feed today; named routes would add server-side default sorts so that clients
  agree on what "latest" means. That is a real improvement and a separate one.
