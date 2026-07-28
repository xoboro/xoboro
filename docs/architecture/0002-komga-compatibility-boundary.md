# ADR 0002: Komga compatibility is an explicit boundary

- Status: Superseded by ADR 0082
- Date: 2026-07-27
- Baseline: Komga 1.25.0

## Decision

Xoboro targets 99.9% observable backend compatibility with Komga 1.25.0.
Compatibility code lives under `compatibility` and translates Komga requests,
responses, authentication rules, protocol details, and errors to Xoboro
application services.

The Xoboro domain model and database schema do not copy API DTOs or persistence
entities simply to preserve Komga's internal implementation.

## Compatibility evidence

A feature is compatible only when all applicable evidence exists:

1. Endpoint or protocol contract test.
2. Authorization and restriction tests.
3. Persistence and restart test.
4. Error and unavailable-storage behavior test.
5. Golden response comparison against Komga 1.25.0.
6. Migration verification where historical state is involved.

The historical source of truth was `docs/komga-parity.md`. The active
feature-based decision and release ledger are ADR 0082 and
`docs/feature-coverage.md`.
