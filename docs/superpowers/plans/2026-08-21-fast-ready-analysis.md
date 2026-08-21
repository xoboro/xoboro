# Fast Reader-Ready Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a usable CBZ page manifest at 10 or more items per second before optional media enrichment finishes.

**Architecture:** `ANALYZE_BOOK` becomes the reader-ready stage: a focused indexer reads only the ZIP central directory and persists `READY`, falling back to the existing full analyzer when the fast path cannot answer. It then emits a deterministic low-priority `ENRICH_BOOK` task that runs the existing full analysis and post-analysis metadata/artwork work. Page and media-file persistence uses JDBC batches within the existing transaction.

**Tech Stack:** Kotlin/JVM, JUnit/kotlin-test, jOOQ, SQLite, Gradle

**Spec:** `docs/superpowers/specs/2026-08-21-fast-ready-analysis-design.md`

## Global Constraints

- One primary component or class per newly created file.
- Add no dependencies and no schema migration.
- Preserve PDF, EPUB, RAR, corrupt, and encrypted-media behavior through full-analysis fallback.
- Preserve durable queue retry, lease, priority, and dead-letter semantics.
- Do not commit, push, or modify the user's unrelated `.claude/` content.
- `./gradlew check` must pass before completion.

---

### Task 1: Reader-ready CBZ indexer

**Files:**
- Create: `server/media/src/main/kotlin/io/xoboro/server/media/ReaderReadyBookIndexer.kt`
- Create: `server/media/src/test/kotlin/io/xoboro/server/media/ReaderReadyBookIndexerTest.kt`

**Interfaces:**
- Consumes: `BookRepository`, `LibraryRepository`, `BookMediaRepository`, `SourceRandomAccess`, `ZipDirectoryMediaAnalyzer`, and `AnalyzeBook`.
- Produces: `ReaderReadyBookIndexer.execute(bookId: BookId): BookMedia`.

- [x] **Step 1: Write failing tests for fast, unchanged-ready, and fallback behavior**

```kotlin
val result = indexer.execute(BOOK_ID)
assertEquals(MediaStatus.READY, result.status)
assertEquals(0, randomAccess.entryBodyReads)
assertEquals(0, fullAnalyzerCalls)
```

The fast fixture requests `hashFiles=true` and `analyzeDimensions=true`; the assertion proves these enrichment settings do not block the first reader-ready result. A previously ready fixture must return its existing media without opening the source. A PDF or unreadable ZIP fixture must invoke the injected full analyzer exactly once.

- [x] **Step 2: Run the focused test and confirm it fails because the indexer does not exist**

Run: `./gradlew :server:media:test --tests '*ReaderReadyBookIndexerTest'`

- [x] **Step 3: Implement the focused indexer**

```kotlin
class ReaderReadyBookIndexer(
  private val books: BookRepository,
  private val libraries: LibraryRepository,
  private val media: BookMediaRepository,
  randomAccesses: Collection<SourceRandomAccess>,
  private val fallback: (BookId) -> BookMedia,
  private val zipDirectoryAnalyzer: ZipDirectoryMediaAnalyzer = ZipDirectoryMediaAnalyzer(),
  private val currentTimeMillis: () -> Long,
) {
  fun execute(bookId: BookId): BookMedia
}
```

Return existing `READY` media unchanged. For CBZ plus random access, read and persist the central-directory result. Catch only the same I/O/security/unreadable exceptions already used by `AnalyzeBook`; otherwise call `fallback(bookId)`.

- [x] **Step 4: Run the focused test and all media tests**

Run: `./gradlew :server:media:test`

---

### Task 2: Durable low-priority enrichment stage

**Files:**
- Create: `server/tasks/src/main/kotlin/io/xoboro/server/tasks/EnrichBookTaskEmitter.kt`
- Create: `server/tasks/src/main/kotlin/io/xoboro/server/tasks/EnrichBookTaskHandler.kt`
- Create: `server/tasks/src/test/kotlin/io/xoboro/server/tasks/EnrichBookTaskEmitterTest.kt`
- Create: `server/tasks/src/test/kotlin/io/xoboro/server/tasks/EnrichBookTaskHandlerTest.kt`
- Modify: `server/app/src/main/kotlin/io/xoboro/server/XoboroRuntime.kt`
- Modify: `server/tasks/src/test/kotlin/io/xoboro/server/tasks/AnalyzeBookWorkerIntegrationTest.kt`

**Interfaces:**
- Consumes: `ReaderReadyBookIndexer.execute`, existing `AnalyzeBook.execute`, `DurableTaskQueue`, and existing post-analysis callbacks.
- Produces: `EnrichBookTaskEmitter.enrich(bookId: BookId, priority: Int = TaskPriority.LOW): Boolean` and `EnrichBookTaskHandler` with task type `ENRICH_BOOK`.

- [x] **Step 1: Write failing emitter and handler tests**

```kotlin
assertTrue(emitter.enrich(BOOK_ID))
val claimed = requireNotNull(queue.claimNext("worker", "lease", 10, 1000))
assertEquals("ENRICH_BOOK_${BOOK_ID.value}", claimed.task.id)
assertEquals(TaskPriority.LOW, claimed.task.priority)
```

Also enqueue a high-priority `ANALYZE_BOOK` and assert it is claimed before the low-priority enrichment row. Handler tests prove payload validation and `enrich` then `afterEnrich` ordering.

- [x] **Step 2: Run focused tests and confirm missing types fail compilation**

Run: `./gradlew :server:tasks:test --tests '*EnrichBookTask*'`

- [x] **Step 3: Implement emitter and handler in separate files**

```kotlin
class EnrichBookTaskEmitter(
  private val books: BookRepository,
  private val queue: DurableTaskQueue,
  private val currentTimeMillis: () -> Long,
) {
  fun enrich(bookId: BookId, priority: Int = TaskPriority.LOW): Boolean
  companion object { fun taskId(bookId: BookId): String }
}
```

```kotlin
class EnrichBookTaskHandler(
  private val enrichBook: (BookId) -> Unit,
  private val afterEnrich: (BookId) -> Unit = {},
  private val json: Json = Json,
) : TaskHandler
```

- [x] **Step 4: Rewire runtime stages**

Construct `ReaderReadyBookIndexer` beside `AnalyzeBook`. Register `AnalyzeBookTaskHandler` with the reader-ready indexer and `afterAnalyze = enrichmentEmitter::enrich`. Register `EnrichBookTaskHandler` with the full analyzer and move the existing metadata/artwork callbacks into `afterEnrich` unchanged.

- [x] **Step 5: Extend the worker integration test**

Run the first worker turn and assert media is `READY` plus one pending enrichment task. Run the second turn and assert dimensions/full enrichment are persisted and the queue drains.

- [x] **Step 6: Run task and app tests**

Run: `./gradlew :server:tasks:test :server:app:test`

---

### Task 3: Batched media persistence

**Files:**
- Modify: `server/persistence/src/main/kotlin/io/xoboro/server/persistence/JooqBookMediaRepository.kt`
- Modify: `server/persistence/src/test/kotlin/io/xoboro/server/persistence/JooqBookMediaRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `BookMedia` pages/files and `DSLContext.batch(sql, bindings)`.
- Produces: unchanged `BookMediaRepository.upsert(media: BookMedia)` behavior with batched page/file writes.

- [x] **Step 1: Add a replacement-semantics regression test**

Persist multiple pages and files, replace them with a differently ordered set, and assert the exact returned page/file order and absence of stale rows.

- [x] **Step 2: Run the focused persistence test before changing production code**

Run: `./gradlew :server:persistence:test --tests '*JooqBookMediaRepositoryTest'`

- [x] **Step 3: Replace per-row execute loops with batches**

```kotlin
transaction.batch(
  INSERT_PAGE_SQL,
  *media.pages.map { page -> pageBindings(media.bookId, page) }.toTypedArray(),
).execute()
```

Use the same shape for `media_file`; skip the batch when the collection is empty. Keep deletes and all inserts inside the existing transaction.

- [x] **Step 4: Run all persistence tests**

Run: `./gradlew :server:persistence:test`

---

### Task 4: Throughput measurement and documentation

**Files:**
- Create: `server/app/src/test/kotlin/io/xoboro/server/perf/ReaderReadyThroughputTest.kt`
- Modify: `server/app/build.gradle.kts`
- Modify: `docs/performance.md`
- Modify: `docs/feature-coverage.md`

**Interfaces:**
- Consumes: production scanner, `ReaderReadyBookIndexer`, local random access, SQLite repository, and synthetic CBZ fixtures.
- Produces: Gradle task `readerReadyThroughput` and a report containing items, pages, elapsed milliseconds, and items/second.

- [x] **Step 1: Write the performance measurement**

Generate at least 10,000 synthetic CBZs with approximately 33 central-directory entries each, scan them, index them through the production reader-ready path, and print:

```text
xoboro.reader_ready.items=10000
xoboro.reader_ready.pages=330000
xoboro.reader_ready.elapsed_ms=<measured>
xoboro.reader_ready.items_per_second=<measured>
```

Assert correctness (all media rows `READY` with exact page counts). Keep the throughput threshold out of default `check`; the explicit task fails when measured throughput is below 10 items/second.

- [x] **Step 2: Run the measurement and retain the complete output**

Run: `./gradlew readerReadyThroughput --rerun --console=plain`

- [x] **Step 3: If below target, profile only the measured path and optimize the proven bottleneck**

Use elapsed sub-measurements for central-directory reads and repository upserts. Do not weaken the 10 items/second target or shrink fixture page count to make it pass.

- [x] **Step 4: Document the measured result and two-stage semantics**

Record hardware context, fixture shape, exact command, throughput, and the distinction between reader-ready completion and enrichment completion in `docs/performance.md`; update the release-gate row in `docs/feature-coverage.md`.

- [x] **Step 5: Run all verification gates**

Run:

```bash
./gradlew check
./gradlew readerReadyThroughput --rerun --console=plain
git diff --check
```

After local verification, build and deploy the arm64 image to the Mac mini without committing or
pushing. Verify health/readiness and the deployed image ID. Do not delete production metadata just
to manufacture an unready transition; the synthetic worker integration and throughput gates cover
that transition without altering the user's library.
