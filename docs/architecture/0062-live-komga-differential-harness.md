# ADR 0062: Live Komga differential harness

- Status: accepted
- Date: 2026-07-28

## Context

Route and contract tests prove that Xoboro implements the pinned operation
inventory, but they cannot certify observable equality with a running Komga
1.25.0 server. Responses contain dynamic identifiers and URLs, JSON arrays that
represent sets, binary payloads, and environment-specific fields. Ad hoc
comparison scripts tend to hide differences or leak credentials and fixture
data.

## Decision

- Add a standalone `komga-differential` compatibility module that sends each
  request to a reference Komga server and an Xoboro candidate.
- Compare status, normalized content type, selected headers, and body for every
  case.
- Compare JSON structurally after sorting object keys. Allow only explicit
  JSON-Pointer patterns for ignored values and unordered arrays, including a
  single-segment wildcard for generated collection members.
- Compare text after line-ending normalization and binary bodies by SHA-256.
- Reject absolute request targets, fragments, malformed methods, and header
  injection.
- Read authorization exclusively from environment variables rather than suite
  files or command-line arguments.
- Pin the reference container by digest and run the anonymous baseline against
  two fresh, isolated servers in CI.
- Keep all committed suites synthetic and review every normalization rule.

## Consequences

The parity ledger can now distinguish implemented behavior from behavior
certified against the target release. A mismatch fails with the case and
response aspect instead of updating a golden file automatically. Authenticated
catalog, media, organization, and error suites can be added without changing
the runner, while secrets and personal media remain outside the repository.
