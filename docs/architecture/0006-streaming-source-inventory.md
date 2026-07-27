# ADR 0006: Streaming metadata-only source inventory

- Status: accepted
- Date: 2026-07-27

## Context

Large libraries can contain hundreds of gigabytes and many thousands of files. A scan
must discover filesystem changes, but opening archives, hashing content, or retaining
the complete tree during discovery makes scan latency and memory usage scale poorly.
Future network sources also need the same discovery contract without pretending to be
local paths.

## Decision

- Expose source inventory as a callback stream of portable file metadata.
- Emit opaque item ID, normalized relative path, name, lowercase extension, size, and
  modification timestamp.
- Do not open file contents during inventory.
- Prune hidden and configured excluded directories before descending into them.
- Report inaccessible entries separately and continue where the source permits.
- Return aggregate counters for logging and metrics.
- Keep media detection, archive parsing, hashes, metadata import, and thumbnail work in
  separately prioritized durable tasks.

The local adapter performs one `walkFileTree` pass and reads attributes supplied by the
visitor. It does not build Komga's scan-wide series/book/sidecar maps.

## Consequences

Discovery memory is bounded by traversal state and downstream callback behavior rather
than total library size. Reconciliation can batch database writes and schedule expensive
work independently. Ordering is source-defined and must not be used as catalog ordering.
Exact reconciliation, move detection, media filtering, and remote pagination remain
follow-up capabilities.
