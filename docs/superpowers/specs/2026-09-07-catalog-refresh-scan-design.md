# Catalog Refresh and Scan Design

## Context

The reader home screen currently treats query text as the complete identity of a
search. A library change therefore leaves an old search in place, while shelf and
series requests from the previous library can finish after the new requests and
overwrite the new view. Catalog events trigger an immediate five-request refresh
for every event, so a scan that publishes many mutations creates a request storm.

Manual "all libraries" synchronization sends every scan request concurrently.
The durable queue excludes scans only within one library, so two workers can scan
different library roots at once. On the deployed catalog this increased a comics
scan from the usual 6--11 seconds to 22.9 seconds while also competing with reader
queries for SQLite and filesystem resources.

Regular local scans also stage every visible media candidate even when none of its
metadata has changed. The deployed libraries contain roughly 300,000 books, making
that repeated database work material even though the inventory traversal itself is
already metadata-only and streaming.

## Goals

- Make the visible home state belong only to the selected library and newest request.
- Cancel superseded browser requests and never report cancellation as a failure.
- Collapse catalog event bursts into one relevant refresh.
- Keep durable server maintenance independent of UI navigation.
- Prevent two library scans from running concurrently without reducing concurrency
  for unrelated analysis, metadata, and artwork work.
- Skip database reconciliation for a provably unchanged local inventory while
  preserving deep-scan and failure semantics.
- Keep the existing search index, API shapes, and user-visible behavior outside
  these corrections.

## Non-goals

- Replacing SQLite FTS with Lucene or another search engine.
- Making an accepted `202` scan request wait for task completion.
- Cancelling a durable scan because a reader changes screens or libraries.
- Adding a filesystem watcher or trusting directory modification time as the sole
  change signal.
- Optimizing WebDAV inventory without a source-native trustworthy snapshot token.

## Considered approaches

### Lower the complete task pool to one

This prevents parallel scans but also serializes media analysis, metadata, and
artwork work that can usefully overlap. It treats a scan-specific contention problem
as a reason to disable all background concurrency, so it is rejected.

### Send scan requests sequentially only in the web client

Sequential HTTP requests are not sequential task execution: each request returns as
soon as its durable task is accepted, and another worker can claim it before the next
request arrives. Scheduled scans and API clients would also bypass the rule. Client
sequencing is still useful for predictable retry reporting, but cannot be the server
invariant by itself.

### Skip directories using only directory mtime

Creating, deleting, or renaming an entry normally changes its parent directory mtime,
but replacing file contents does not have to. A normal scan must not silently miss a
changed archive, so this Kavita-style shortcut is rejected as the correctness boundary.

### Chosen: request ownership, scan exclusion, and inventory fingerprints

The home screen owns one controller/generation for catalog refreshes and one for quick
search. The durable queue keeps the existing per-library group and adds a second,
scan-wide exclusion key. Local inventory supplies an order-independent fingerprint of
the same metadata reconciliation already uses. A persisted successful checkpoint lets
a later regular scan perform one metadata walk and return a no-op result before opening
a reconciliation session when the fingerprint and relevant configuration are equal.

## Reader request lifecycle

The catalog API helpers accept an optional `AbortSignal` and pass it to the shared HTTP
client, which already preserves `AbortError` rather than wrapping it as a network error.

`Home.svelte` maintains two independent request owners:

- Quick search captures `(trimmed query, selected library)` and aborts its predecessor.
  Changing the selected library immediately re-runs a non-empty query for the new
  library. Only the current generation may assign results, errors, or loading state.
- Home catalog refresh captures `(selected library, page)` and aborts its predecessor.
  The four shelves and series page share that controller. A late response from an old
  library or page cannot assign state. Component destruction aborts both owners and
  clears every timer.

The advanced search keeps its existing controller. The home library switch remains a
home browsing scope; it does not silently overwrite filters the reader explicitly chose
inside advanced search.

## Event filtering and coalescing

Catalog events already carry `libraryId`. Home ignores an event when a concrete library
is selected and the event belongs to another library. In all-libraries mode every
catalog event is relevant.

Relevant catalog events schedule, rather than directly execute, a refresh. A trailing
200 ms window collapses any burst into one refresh. The scheduled refresh also re-runs
an active quick search so newly indexed titles become visible without editing the query.
Progress events use the same scheduler but request shelves only; they do not reload the
series grid or quick search. A resync-required control frame bypasses library filtering
but still coalesces with any already scheduled full refresh.

The coalescer is a small standalone module with one responsibility: merge requested
refresh scopes and execute the strongest scope once at the end of the window. `full`
dominates `shelves`, and disposal cancels the pending callback.

## Manual synchronization

The reader submits selected scan requests in library order, recording each failure and
continuing with the remaining libraries. Retry retains only failed targets. This makes
request behavior deterministic and avoids an avoidable HTTP burst, while the durable
queue remains the authority for execution ordering.

The queue supports an optional `exclusionKey` in addition to the existing `groupId`.
`groupId` retains per-library exclusion between a scan and library-level maintenance.
Every `SCAN_LIBRARY` task also receives the constant scan exclusion key, so claim logic
will not lease another scan while any unexpired scan lease with that key is running.
Other task types remain eligible for the remaining workers.

The schema change is additive and nullable. Existing tasks and older server binaries
continue to read the table; downgrade still follows the documented backup/restore rule.

## Unchanged local-scan fast path

### Fingerprint contents

Local inventory computes a SHA-256 Merkle fingerprint while walking. Each regular,
non-hidden file contributes:

- portable relative path;
- source identity when available;
- byte size;
- modification timestamp.

Directory nodes combine their immediate file and child-directory hashes after sorting
by portable entry name. Memory is bounded by the current directory entries and traversal
depth, not total library size. Excluded and hidden subtrees contribute no file metadata,
matching the inventory presented to reconciliation.

The checkpoint also carries the source ID, root item ID, and the candidate-affecting
library settings: CBX/PDF/EPUB enablement, one-shots marker, and directory exclusions.
Changing any of those forces reconciliation even when the filesystem fingerprint is
unchanged.

### Scan flow

1. Deep scans always use the existing complete inventory and reconciliation path.
2. A regular local scan with no successful checkpoint uses the complete path once and
   stores the fingerprint returned by that same walk after a non-partial commit.
3. A regular local scan with a checkpoint probes the current fingerprint without
   staging candidates.
4. Equal fingerprint and configuration return an all-zero, non-partial result without
   creating a scan session or catalog events.
5. A changed fingerprint performs the existing complete path. The second walk is the
   cost paid only when something changed; its successful fingerprint replaces the
   checkpoint.
6. Probe failures, inventory entry failures, unavailable roots, unknown snapshot
   support, and checkpoint storage failures fail safe into the existing full-scan path
   or leave the previous checkpoint untouched. They never produce a false unchanged
   result.

This optimization preserves the current definition of change. Xoboro already compares
path, identity refresh, size, and mtime rather than file content bytes during inventory;
the fingerprint neither weakens nor expands that contract.

## Persistence and failure behavior

A new `catalog_scan_checkpoint` table has one row per library and a foreign key with
cascade deletion. Checkpoint replacement happens only after catalog reconciliation has
committed with zero inventory failures. If the process dies between reconciliation and
checkpoint replacement, the next scan performs extra work but remains correct.

Fingerprint probing never updates availability by itself. An unreadable root follows
the existing `SourceInventoryUnavailableException` path and marks the library
unavailable. A successfully probed unchanged root marks it available through the normal
task handler completion path.

## Tests

Frontend regression tests prove:

- switching from comics to webtoon aborts the comics quick search and renders only the
  webtoon response;
- late shelf/grid responses from comics cannot overwrite webtoon state;
- unrelated-library events cause no refresh;
- a burst of relevant catalog events causes one full refresh and one active-search
  refresh;
- progress bursts refresh shelves without reloading the series grid;
- all-library scan requests are issued sequentially and failed targets remain retryable;
- unmount aborts requests and cancels scheduled refreshes.

Backend tests prove:

- two scans from different libraries cannot be claimed concurrently while an unrelated
  task still can;
- the second scan becomes claimable after the first completes;
- migration and task mapping preserve both exclusion fields;
- local fingerprints are order-independent and change for path, identity, size, or mtime;
- equal fingerprints skip reconciliation and emit an all-zero result;
- changed settings, deep scans, changed fingerprints, failures, and unsupported source
  inventories keep the full-scan path;
- checkpoints are written only after successful, complete reconciliation.

The final gate is `npm test`, `npm run build`, and `./gradlew check`, followed by the
container acceptance gate before merge/deployment.

## Deployment

The change is delivered through a pull request into `main`. Automatic CI and container
workflows remain enabled. After merge, deployment pulls the GHCR image produced from the
merged `main`, recreates the macmini service, verifies its health/readiness endpoints,
and performs a browser-level search/library-switch smoke check. No image is side-loaded.
