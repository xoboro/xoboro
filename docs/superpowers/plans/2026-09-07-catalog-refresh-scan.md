# Catalog Refresh and Scan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make library switching deterministic, collapse catalog-event request storms, serialize filesystem scans, and skip reconciliation for unchanged local inventories.

**Architecture:** The reader gives each request family its own abortable owner and routes event invalidations through a trailing coalescer. Durable tasks gain a second nullable exclusion key used by every library scan. Local inventories expose a metadata fingerprint, and a database checkpoint lets `CatalogScanner` return a no-op result before staging when a regular scan is unchanged.

**Tech Stack:** Svelte 5, Vitest/Testing Library, Kotlin Multiplatform, jOOQ, SQLite/Flyway, Gradle, Docker Compose, GitHub Actions/GHCR.

**Spec:** `docs/superpowers/specs/2026-09-07-catalog-refresh-scan-design.md`

## Global Constraints

- Preserve the native API and SQLite FTS search implementation.
- Introduce no new dependency.
- Keep durable background work independent of browser navigation.
- Deep scans and non-fingerprinting sources always perform full reconciliation.
- Never use directory mtime alone as proof that a library is unchanged.
- Preserve unrelated `.gitignore`, `.claude/`, and `simple-komga-video.html` worktree changes.
- Keep one primary component/class/module per file.

---

### Task 1: Home refresh coordinator and abortable request ownership

**Files:**
- Create: `web/src/reader/homeRefreshCoordinator.js`
- Create: `web/tests/homeRefreshCoordinator.test.js`
- Modify: `web/src/lib/api/catalog.js`
- Modify: `web/src/reader/Home.svelte`
- Modify: `web/tests/ReaderHome.test.js`

**Interfaces:**
- Produces: `createHomeRefreshCoordinator({ selectedLibrary, searchActive, refreshCatalog, refreshShelves, refreshSearch, schedule, cancel, delay })`.
- Produces: `onCatalog(message)`, `onProgress()`, `onResync()`, and `dispose()` coordinator methods.
- Produces: optional `signal` members on `readFeed` and `listSeries` options.

- [ ] **Step 1: Write coordinator failure tests**

Add literal behavior tests proving that an event for `lib-comics` is ignored while
`lib-webtoon` is selected, three relevant catalog events schedule one callback after
200 ms, a full refresh dominates a pending shelf refresh, and disposal cancels the
pending callback.

- [ ] **Step 2: Run the coordinator test and verify RED**

Run: `cd web && npm test -- homeRefreshCoordinator.test.js`

Expected: FAIL because `homeRefreshCoordinator.js` does not exist.

- [ ] **Step 3: Implement the trailing coordinator**

Implement one pending timer and a pending scope of `none`, `shelves`, or `full`.
Every accepted event resets the timer. On flush, `full` invokes catalog refresh and,
when `searchActive()` is true, quick-search refresh; `shelves` invokes only shelf
refresh. The event filter is:

```js
const selected = selectedLibrary()
if (selected !== null && message.libraryId !== selected) return
```

- [ ] **Step 4: Run the coordinator test and verify GREEN**

Run: `cd web && npm test -- homeRefreshCoordinator.test.js`

Expected: PASS.

- [ ] **Step 5: Write Home lifecycle failure tests**

Add component tests that hold fetch promises open and prove:

```js
expect(comicsSearch.signal.aborted).toBe(true)
expect(screen.getByTestId('home-search-results')).toHaveTextContent('Webtoon Answer')
expect(screen.getByTestId('home-search-results')).not.toHaveTextContent('Comics Answer')
```

Add the equivalent stale-response assertion for the series grid after switching
libraries, and assert request signals are aborted on component unmount.

- [ ] **Step 6: Run Home tests and verify RED**

Run: `cd web && npm test -- ReaderHome.test.js`

Expected: the new library-switch and abort assertions FAIL because current requests
have no signals and quick search does not restart.

- [ ] **Step 7: Implement independent request owners**

Add separate controller/generation pairs for quick search, shelves, and series.
Capture the selected library before each request, pass the signal through catalog API
helpers, and permit assignments only from the current generation. Ignore
`AbortError`. On `chooseLibrary`, clear the debounce, refresh catalog state, and
immediately re-run a non-empty quick query. On destroy, abort all owners and dispose
the refresh coordinator.

- [ ] **Step 8: Wire coalesced SSE handling**

Replace direct event callbacks with coordinator methods. Catalog events use
`onCatalog(message)`, progress events use `onProgress`, and resync frames use
`onResync`. Keep initial mount and explicit retry refreshes immediate.

- [ ] **Step 9: Run focused frontend tests**

Run: `cd web && npm test -- homeRefreshCoordinator.test.js ReaderHome.test.js`

Expected: PASS with no unhandled abort rejection.

- [ ] **Step 10: Commit the reader lifecycle slice**

```bash
git add web/src/reader/homeRefreshCoordinator.js web/tests/homeRefreshCoordinator.test.js web/src/lib/api/catalog.js web/src/reader/Home.svelte web/tests/ReaderHome.test.js
git commit -m "fix: own reader requests by library"
```

### Task 2: Sequential manual synchronization requests

**Files:**
- Modify: `web/src/reader/Home.svelte`
- Modify: `web/tests/ReaderHome.test.js`

**Interfaces:**
- Consumes: existing `triggerLibraryTask(libraryId, 'scan')`.
- Preserves: `retryTargets` contains only targets rejected by their latest request.

- [ ] **Step 1: Strengthen the sync ordering test**

Hold the first POST unresolved and assert no second POST exists. Resolve the first,
then assert the second target is submitted. Retain the partial-failure retry test.

- [ ] **Step 2: Run the sync tests and verify RED**

Run: `cd web && npm test -- ReaderHome.test.js -t "queues one scan per library"`

Expected: FAIL because `Promise.allSettled(map(...))` starts both requests together.

- [ ] **Step 3: Implement sequential outcome collection**

Replace the parallel map with a `for...of` loop that awaits each request, records a
fulfilled/rejected outcome, and continues after failure. Derive the accepted count,
first error, and retry targets from that ordered outcome list.

- [ ] **Step 4: Run manual-sync tests and verify GREEN**

Run: `cd web && npm test -- ReaderHome.test.js -t "manual library sync"`

Expected: PASS.

- [ ] **Step 5: Commit the request sequencing slice**

```bash
git add web/src/reader/Home.svelte web/tests/ReaderHome.test.js
git commit -m "fix: sequence manual scan requests"
```

### Task 3: Durable scan-wide exclusion

**Files:**
- Create: `server/persistence/src/main/resources/db/migration/V40__task_exclusion_key.sql`
- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/TaskQueue.kt`
- Modify: `core/application/src/commonTest/kotlin/io/xoboro/core/application/TaskQueueTest.kt`
- Modify: `server/persistence/src/main/kotlin/io/xoboro/server/persistence/JooqDurableTaskQueue.kt`
- Modify: `server/persistence/src/test/kotlin/io/xoboro/server/persistence/JooqDurableTaskQueueTest.kt`
- Modify: `server/tasks/src/main/kotlin/io/xoboro/server/tasks/ScanLibraryTask.kt`
- Modify: `server/tasks/src/test/kotlin/io/xoboro/server/tasks/ScanLibraryTaskTest.kt`

**Interfaces:**
- Produces: nullable `DurableTask.exclusionKey: String? = null`.
- Produces: `ScanLibraryTaskEmitter.SCAN_EXCLUSION_KEY` assigned to every scan task.
- Preserves: existing per-library `groupId` semantics.

- [ ] **Step 1: Write domain validation and queue behavior failure tests**

Add a task-domain test rejecting a blank non-null exclusion key. Add a real SQLite
queue test with scan A (`groupId=lib-a`, `exclusionKey=library-scan`), scan B
(`groupId=lib-b`, same exclusion), and an unrelated task. After scan A is claimed,
the unrelated task must remain claimable while scan B does not become claimable until
scan A completes.

- [ ] **Step 2: Run focused backend tests and verify RED**

Run: `./gradlew :core:application:jvmTest :server:persistence:test --tests '*JooqDurableTaskQueueTest*'`

Expected: compilation/test failure because `exclusionKey` and migration do not exist.

- [ ] **Step 3: Add the additive migration and task field**

Migration content:

```sql
ALTER TABLE task ADD COLUMN exclusion_key TEXT
  CONSTRAINT task_exclusion_not_blank
  CHECK (exclusion_key IS NULL OR length(trim(exclusion_key)) > 0);

CREATE INDEX task_active_exclusion_idx
  ON task (exclusion_key, state, lease_expires_at_ms);
```

Add the nullable field and validation to `DurableTask`.

- [ ] **Step 4: Persist and enforce the exclusion key**

Include `exclusion_key` in enqueue/upsert, claim candidate predicates, `RETURNING`,
and claimed-task mapping. Both normal and aged candidate CTEs must reject a candidate
whose non-null exclusion key matches an unexpired running task.

- [ ] **Step 5: Assign the scan exclusion key**

Keep `groupId = libraryId.value` and add:

```kotlin
exclusionKey = SCAN_EXCLUSION_KEY
```

with one stable constant for every deep and regular library scan.

- [ ] **Step 6: Run queue and scan-task tests and verify GREEN**

Run: `./gradlew :core:application:jvmTest :server:persistence:test :server:tasks:test --tests '*JooqDurableTaskQueueTest*' --tests '*ScanLibraryTaskTest*'`

Expected: PASS.

- [ ] **Step 7: Commit the durable exclusion slice**

```bash
git add core/application server/persistence server/tasks
git commit -m "fix: serialize library scan execution"
```

### Task 4: Local inventory fingerprint checkpoints

**Files:**
- Create: `core/application/src/commonMain/kotlin/io/xoboro/core/application/SourceInventoryFingerprint.kt`
- Create: `core/application/src/commonMain/kotlin/io/xoboro/core/application/CatalogScanCheckpointStore.kt`
- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/CatalogScanner.kt`
- Modify: `core/application/src/commonTest/kotlin/io/xoboro/core/application/CatalogScannerTest.kt`
- Modify: `server/sources/local/src/main/kotlin/io/xoboro/server/sources/local/LocalSourceInventory.kt`
- Modify: `server/sources/local/src/test/kotlin/io/xoboro/server/sources/local/LocalSourceInventoryTest.kt`
- Create: `server/persistence/src/main/resources/db/migration/V41__catalog_scan_checkpoint.sql`
- Create: `server/persistence/src/main/kotlin/io/xoboro/server/persistence/JooqCatalogScanCheckpointStore.kt`
- Create: `server/persistence/src/test/kotlin/io/xoboro/server/persistence/JooqCatalogScanCheckpointStoreTest.kt`
- Modify: `server/app/src/main/kotlin/io/xoboro/server/XoboroRuntime.kt`

**Interfaces:**
- Produces: `FingerprintingSourceInventory.fingerprint(rootItemId, directoryExclusions)` returning `SourceInventoryFingerprint(value, failedEntries)`.
- Produces: `CatalogScanCheckpointStore.matches(library, fingerprint)` and `replace(library, fingerprint, completedAtMillis)`.
- Consumes: optional checkpoint store in `CatalogScanner`.

- [ ] **Step 1: Write local fingerprint failure tests**

Using synthetic temporary files, prove identical trees fingerprint equally regardless
of creation order, and prove changing each of relative path, source identity, size, and
mtime changes the literal fingerprint value. Prove hidden/excluded entries do not
affect it and inaccessible entries prevent an unchanged decision.

- [ ] **Step 2: Run local-source tests and verify RED**

Run: `./gradlew :server:sources:local:test --tests '*LocalSourceInventoryTest*'`

Expected: compilation failure because fingerprinting is absent.

- [ ] **Step 3: Implement order-independent metadata fingerprinting**

Add a fingerprinting inventory interface in core. In `LocalSourceInventory`, reuse
the existing metadata walk and feed each `SourceFile` into SHA-256 using length-prefixed
UTF-8 values and fixed-width longs. XOR the 32-byte per-file digests and include the
emitted file count in the final digest. Return the inventory failure count with the
fingerprint. Add no content reads.

- [ ] **Step 4: Run local-source tests and verify GREEN**

Run: `./gradlew :server:sources:local:test --tests '*LocalSourceInventoryTest*'`

Expected: PASS.

- [ ] **Step 5: Write scanner fast-path failure tests**

Use a recording fingerprint inventory, checkpoint store, and reconciliation store to
prove: equal regular fingerprint never calls `begin`; a changed fingerprint does;
deep scan never probes; failed probes never skip; and checkpoint replacement happens
only after a successful non-partial completion. The unchanged return must be the
literal all-zero `CatalogReconciliationResult` with `partial=false`.

- [ ] **Step 6: Run core scanner tests and verify RED**

Run: `./gradlew :core:application:jvmTest --tests '*CatalogScannerTest*'`

Expected: FAIL because `CatalogScanner` does not consult checkpoints.

- [ ] **Step 7: Implement the scanner fast path**

For a regular scan with fingerprint and checkpoint support, probe first. Skip only
when `failedEntries == 0` and `matches` is true. Otherwise execute the existing scan.
After successful non-partial completion, replace the checkpoint using the probe value.
If checkpoint lookup/write is unavailable, retain full reconciliation and correctness.

- [ ] **Step 8: Run core scanner tests and verify GREEN**

Run: `./gradlew :core:application:jvmTest --tests '*CatalogScannerTest*'`

Expected: PASS.

- [ ] **Step 9: Write persistence checkpoint failure tests**

Create two libraries in a real migrated SQLite database. Prove exact matching includes
source/root, relevant settings, sorted exclusions, and fingerprint; a changed setting
does not match; replacement is idempotent; deleting a library cascades its checkpoint.

- [ ] **Step 10: Run persistence tests and verify RED**

Run: `./gradlew :server:persistence:test --tests '*JooqCatalogScanCheckpointStoreTest*'`

Expected: compilation failure because the migration/store do not exist.

- [ ] **Step 11: Implement checkpoint persistence and runtime wiring**

Create `catalog_scan_checkpoint` keyed by `library_id`, with source/root,
configuration token, fingerprint, and completion time. Encode only candidate-affecting
settings in a deterministic token with sorted exclusions. Upsert after successful
reconciliation. Pass `JooqCatalogScanCheckpointStore(database)` to the runtime scanner.

- [ ] **Step 12: Run checkpoint and scanner integration tests**

Run: `./gradlew :core:application:jvmTest :server:sources:local:test :server:persistence:test :server:app:test --tests '*CatalogScan*' --tests '*LocalSourceInventoryTest*'`

Expected: PASS.

- [ ] **Step 13: Commit the unchanged-scan slice**

```bash
git add core/application server/sources/local server/persistence server/app
git commit -m "chore: skip unchanged scan reconciliation"
```

### Task 5: Release-gate documentation and verification

**Files:**
- Modify: `docs/performance.md`
- Modify: `docs/feature-coverage.md` only if the completed behavior changes an existing row's evidence.

**Interfaces:**
- Records: the difference between metadata traversal and reconciliation skip.
- Records: scan-wide exclusion and request-storm prevention evidence.

- [ ] **Step 1: Update measured-behavior documentation**

Describe that regular local scans still perform one metadata walk, skip candidate
staging/reconciliation only on an exact checkpoint match, and that changed scans may
walk twice. Do not claim a performance number not freshly measured.

- [ ] **Step 2: Run formatting and diff checks**

Run: `git diff --check`

Expected: no output and exit 0.

- [ ] **Step 3: Run full frontend gates**

Run: `cd web && npm test && npm run build`

Expected: all Vitest tests pass and Vite production build exits 0.

- [ ] **Step 4: Run full backend gate**

Run: `./gradlew check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run container acceptance**

Run: `scripts/container-acceptance.sh`

Expected: root-context and configured-context application/API/assets checks pass
under the production container confinement.

- [ ] **Step 6: Commit documentation if changed**

```bash
git add docs/performance.md docs/feature-coverage.md
git commit -m "chore: document incremental scan behavior"
```

### Task 6: Pull request, CI, image, and macmini deployment

**Files:**
- No source file changes expected.

**Interfaces:**
- Produces: pushed `fix/catalog-refresh-scan` branch and GitHub pull request.
- Consumes: automatic CI/container workflows and the merged `main` GHCR image.

- [ ] **Step 1: Verify branch contents and user changes**

Run: `git status --short --branch` and `git diff origin/main...HEAD --stat`.

Expected: only task-owned commits are in the branch; unrelated user files remain
uncommitted and untouched.

- [ ] **Step 2: Push and open the pull request**

Push `fix/catalog-refresh-scan`, create a pull request against `main`, and include the
root-cause evidence, behavior changes, migration notes, and exact verification commands.

- [ ] **Step 3: Wait for required CI checks**

Use GitHub CLI read-only status checks until all required checks complete. If a task
test fails, reproduce locally before editing.

- [ ] **Step 4: Merge the pull request**

Merge only after required checks pass. Do not rewrite history or include unrelated
worktree files.

- [ ] **Step 5: Wait for the merged-main GHCR image**

Confirm the container workflow published the `main` image for the merge commit and
that its manifest includes `linux/arm64` for the macmini.

- [ ] **Step 6: Deploy by pulling the registry image**

Back up the deployment database, pull the merged-main image, recreate only the Xoboro
service with Docker Compose, and leave media mounts read-only. Do not side-load a local
image.

- [ ] **Step 7: Verify production behavior**

Confirm container health/readiness, query "조선" while switching comics to webtoon,
verify the old search request cannot overwrite the new result, trigger all-library
sync once, and confirm logs show no overlapping `SCAN_LIBRARY` execution or one-event-
per-refresh request burst.
