# ADR 0052: Set-based scan reconciliation

## Status

Accepted.

## Context

Large libraries can contain tens of thousands of media files and millions of
pages. Inventory should inspect filesystem metadata only, while catalog
reconciliation must not issue one parsed SQL statement or one transaction per
file. Content analysis remains a separately prioritized and parallel worker
stage.

## Decision

- Stream filesystem facts in bounded 500-item batches without opening media
  content.
- Stage each batch with one prepared JDBC batch inside one transaction.
- Classify existing locations with one indexed `UPDATE FROM` join instead of
  repeated correlated lookups.
- Classify moves from pre-aggregated unique candidate and catalog identities.
- Index staged matched-book lookups used by deletion checks and task emission.
- Keep series insertion, book insertion/update, deletion, aggregation, and
  analysis task emission set-based inside one final transaction.
- Cover initial and unchanged 5,000-item inventories with synthetic regression
  tests. Never place timing thresholds in CI; record reproducible measurements
  separately from functional correctness.

## Consequences

Scan time scales primarily with directory enumeration and indexed set
operations rather than SQL parse and commit count. Expensive archive parsing,
page dimensions, hashes, metadata, and artwork remain durable background tasks,
so administrators can tune worker concurrency without making catalog discovery
unsafe.
