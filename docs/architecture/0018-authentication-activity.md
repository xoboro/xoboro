# ADR 0018: Authentication activity

- Status: accepted
- Date: 2026-07-27

## Context

Komga records password, API-key, remember-me, and OAuth authentication outcomes for
security review. Its REST API supports current-user and administrator page queries,
sorting, and latest-activity lookup with an optional API-key filter.

Xoboro must preserve those observable contracts without leaking credentials and must
keep the activity ledger durable across restarts.

## Decision

- Keep the activity model, paging request, and repository boundary in the portable
  core.
- Persist activity in its own append-oriented SQLite table with indexes for user and
  reverse-chronological access.
- Record password and `X-API-Key` success and failure at the authentication adapter,
  including connection IP and User-Agent when available.
- Store the API key ID and comment for successful key authentication. For a failed key,
  store only a deterministic SHA-512 fingerprint; never store the submitted token.
- Expose Komga-compatible current-user, administrator, and latest-activity routes,
  including default `dateTime,desc` sorting and Spring-style page envelopes.
- Delete a user's linked activity through the database foreign-key cascade and expose
  a cutoff operation for scheduled retention cleanup.

## Consequences

Authentication diagnostics survive restarts and can be queried by existing Komga
clients. Failed API-key attempts remain correlatable without making the submitted
credential recoverable from the activity database.

Browser-session, remember-me, OAuth source recording, proxy-aware client-IP policy,
demo mode, and scheduled retention wiring remain separate increments.
