# Reader Experience Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the reader, search, navigation, and event-stream workflows identified by the mobile experience audit while making routed first-page display strictly cheaper than simple-komga's existing request path.

**Architecture:** Add one bounded native media-item context and an optional explicit completion signal, then make each reader consume that context. Keep compatibility APIs unchanged, keep browser state local to the owning screen, and fix stream disconnect handling at the production exception boundary.

**Tech Stack:** Kotlin/JVM, Kotlin Multiplatform, Ktor, kotlinx.serialization, Svelte 5, svelte-spa-router, Vitest, Vite, Docker/GHCR.

**Spec:** `docs/superpowers/specs/2026-09-08-reader-experience-completion-design.md`

## Global Constraints

- Do not add a dependency.
- Do not change the existing colour palette or general information architecture.
- Do not change Komga, Kobo, KOReader, OPDS, or WebPub compatibility response shapes.
- Keep one primary component/class/module per file.
- Add or update an automated test for every production behaviour.
- Run `./gradlew check` before committing backend changes.
- Use synthetic fixtures only; never commit personal names, paths, media, or credentials.
- Preserve `README.md`, `NOTICE`, and `third-party/licenses/KOMGA.txt` attribution.
- Durable scans remain server jobs and are never cancelled by browser navigation.

---

### Task 1: Bounded reader context and width-aware page delivery

**Files:**
- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/CatalogReadModel.kt`
- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/MediaDelivery.kt`
- Modify: `server/media/src/main/kotlin/io/xoboro/server/media/BookContentService.kt`
- Modify: `server/media/src/test/kotlin/io/xoboro/server/media/BookContentServiceTest.kt`
- Modify: `server/persistence/src/main/kotlin/io/xoboro/server/persistence/JooqCatalogReadRepository.kt`
- Modify: `server/persistence/src/test/kotlin/io/xoboro/server/persistence/JooqCatalogReadRepositoryTest.kt`
- Create: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeReaderContextDtos.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeDeliveryDtos.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeDeliveryRoutes.kt`
- Modify: `server/api/src/test/kotlin/io/xoboro/server/api/XoboroNativeDeliveryTest.kt`
- Modify: `server/app/src/main/resources/openapi/xoboro-native-v1.yaml`
- Modify: `docs/api/native-v1.md`

**Interfaces:**
- Produces: `GET /media-items/{mediaItemId}/reader-context` returning `item`, nullable `previousId`/`nextId`, `pages`, and `positions`.
- Produces: `PageImageRequest.maximumWidth: Int?` and native `maxWidth=<1..4096>` query support, mutually exclusive with `maxDimension`.
- Preserves: existing item, adjacent-item, pages, positions, and compatibility routes.

- [ ] **Step 1: Write failing native context tests**

Add tests that request one comic and one EPUB context. Assert literal response fields, that only the correct manifest is populated, adjacent values are identifiers only, unauthorized/missing items are 404, and unready media returns `409 media_not_ready`.

- [ ] **Step 2: Verify context tests fail for the missing route**

Run `./gradlew :server:api:test --tests '*XoboroNativeDeliveryTest*reader context*'` and confirm 404/route absence is the failure.

- [ ] **Step 3: Implement the context response and route**

Add `CatalogBookReaderContext` and `findBookReaderContextByIdOrNull`. Implement it with the existing `(number_sort, relative_uri, id)` sibling ordering and access filter, hydrating only the current item while returning adjacent IDs. Map existing `BookPage` and `BookPosition` objects with the same DTO mapping used by `/pages` and `/positions`; extract mapping helpers rather than duplicate field lists.

- [ ] **Step 4: Write failing maximum-width tests**

Assert `PageImageRequest(maximumWidth = 800)` preserves a `1600x6000` source as `800x3000`, `maxWidth` and `maxDimension` together return `400 invalid_query`, source/raw plus width returns 400, and native ETags differ between source and `maxWidth=800`.

- [ ] **Step 5: Verify maximum-width tests fail**

Run the focused media and delivery tests and confirm the missing property/query is the failure.

- [ ] **Step 6: Implement width-aware resizing**

Extend validation, archive/PDF conversion scaling, early subsampling, and native cache identity. Do not upscale an image whose stored width is already below the bound.

- [ ] **Step 7: Verify backend and document the API**

Run `./gradlew :server:media:test :server:api:test`, update `docs/api/native-v1.md` with literal request/response examples, then run `./gradlew check`.

- [ ] **Step 8: Commit**

Commit as `feat: add bounded media reader context`.

### Task 2: Explicit completion and self-contained conflict reconciliation

**Files:**
- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/ReadProgressLifecycle.kt`
- Modify: `core/application/src/commonTest/kotlin/io/xoboro/core/application/ReadProgressLifecycleTest.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeCatalogDtos.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeProgressRoutes.kt`
- Modify: `server/api/src/test/kotlin/io/xoboro/server/api/XoboroNativeProgressTest.kt`
- Modify: `server/app/src/main/resources/openapi/xoboro-native-v1.yaml`
- Modify: `web/src/lib/http.js`
- Modify: `web/src/lib/errors.js`
- Modify: `web/src/lib/api/progress.js`
- Modify: `web/tests/http.test.js`
- Modify: `web/tests/progress.test.js`
- Modify: `docs/api/native-v1.md`

**Interfaces:**
- Produces: optional request field `completed: Boolean?`; absent retains `page == pageCount`, explicit false/true is authoritative for the native caller.
- Produces: a `409 stale_progress` body carrying the stored native progress as `progress`.
- Produces: `writeProgress(id, { page, locator, completed, keepalive })` with no conflict follow-up GET.

- [ ] **Step 1: Write lifecycle and route tests for explicit completion**

Assert page `pageCount` plus `completed=false` stores incomplete, `completed=true` stores complete, and an omitted value retains historical last-page completion. Assert non-final `completed=true` is rejected so completion cannot lie about the stored page.

- [ ] **Step 2: Verify the completion tests fail**

Run the focused core/application and native progress tests and confirm the last-page row is incorrectly completed.

- [ ] **Step 3: Implement optional completion**

Add `completed: Boolean? = null` to `updateBookProgression` and the native DTO. Pass it only from the native route; all existing compatibility calls compile unchanged and preserve their semantics.

- [ ] **Step 4: Write failing conflict-body/client tests**

Assert the 409 JSON includes the stored progress. In JavaScript, feed that literal body through the real HTTP error parser and assert `writeProgress` advances its clock without issuing `GET /media-items/{id}`.

- [ ] **Step 5: Implement structured error details and keepalive**

Preserve unknown JSON error fields as `details`, use `details.progress.readAtMillis`, accept `keepalive` in `request`, and pass it to `fetch`. The normal API stays credentialed and same-origin.

- [ ] **Step 6: Verify all progress contracts**

Run `NODE_OPTIONS=--no-experimental-webstorage npm test -- tests/http.test.js tests/progress.test.js`, `./gradlew :core:application:allTests :server:api:test`, then `./gradlew check`.

- [ ] **Step 7: Commit**

Commit as `fix: preserve precise reader completion`.

### Task 3: Comic reader loading, navigation, and progress

**Files:**
- Create: `web/src/reader/imageRequest.js`
- Modify: `web/src/lib/api/catalog.js`
- Modify: `web/src/reader/priorityLoader.js`
- Modify: `web/src/reader/views.js`
- Modify: `web/src/reader/ReaderRoute.svelte`
- Modify: `web/src/reader/Reader.svelte`
- Modify: `web/tests/catalog.test.js`
- Modify: `web/tests/views.test.js`
- Modify: `web/tests/Reader.test.js`

**Interfaces:**
- Consumes: Task 1 context and `maxWidth`; Task 2 explicit completion and keepalive.
- Produces: `readMediaItemReaderContext(id, { signal })`.
- Produces: pure image-width calculation capped at 4096 and doubled for split spreads.
- Produces: priority-loader failure callback, active cancellation, bounded timeout, and one automatic retry.

- [ ] **Step 1: Write failing reader-context and history tests**

Mock `svelte-spa-router` and assert route entry makes exactly one context request, makes no item/pages/adjacent JSON requests, and previous/next/back/list call `replace('/read/id')` or `replace('/series/id')` without `push`.

- [ ] **Step 2: Verify the route/history tests fail**

Run `NODE_OPTIONS=--no-experimental-webstorage npm test -- tests/Reader.test.js tests/EpubReader.test.js` and confirm the old request waterfall/hash assignment causes the failures.

- [ ] **Step 3: Switch ReaderRoute and Reader to context ownership**

Pass `initialContext`; retain direct fallback through `readMediaItemReaderContext` only when a child is mounted alone. Route changes abort the old context and reset the loader before any new image is scheduled.

- [ ] **Step 4: Write failing loader tests**

Using deterministic injected scheduling/timers, assert reset and destroy clear the active node's `src`, a timeout retries once, final failure invokes the callback and starts the next queued image, and a manual retry remount does not restart unrelated images.

- [ ] **Step 5: Implement the loader lifecycle and inline page retry**

Track one active task, clear listeners/timer/source on cancellation, and release the slot exactly once. Preserve same-URL reprioritization. Render a retry control inside the failed slot without changing its reserved aspect ratio.

- [ ] **Step 6: Write failing direction/progress/image-size tests**

Assert RTL left/right swipes match RTL tap/keyboard actions; a centre-band observer can select a slot taller than two viewports; the first half of the final split page writes `completed=false`, the second writes true; scroll-bottom writes true; and a 1600px source displayed at 400 CSS px on DPR 3 requests `maxWidth=1200` while an 800px source stays untransformed.

- [ ] **Step 7: Implement exact view progress and width selection**

Use a centre-line `rootMargin` observer, explicit completion values, scroll-bottom detection, and manifest-aware `pageUrl` options. Use the same loader in paged and scrolling modes.

- [ ] **Step 8: Add non-blocking error and pagehide flushing**

Clear a prior progress error after a successful write, render it as a fixed status that never moves page content, and send the newest pending write with `keepalive=true` from `pagehide`.

- [ ] **Step 9: Verify and commit**

Run the focused Vitest files, then the full web suite and build. Commit as `fix: complete comic reader interactions`.

### Task 4: EPUB reader parity

**Files:**
- Create: `web/src/reader/epubFrame.js`
- Create: `web/src/reader/epubPosition.js`
- Modify: `web/src/reader/EpubReader.svelte`
- Modify: `web/tests/EpubReader.test.js`
- Modify: `web/src/lib/messages/en.js`
- Modify: `web/src/lib/messages/ko.js`

**Interfaces:**
- Consumes: Task 1 context/adjacent IDs and Task 2 explicit completion/keepalive.
- Produces: frame binding that restores and reports in-resource progression without enabling EPUB scripts.
- Produces: locator/page mapping that never sends a spine position outside `1..media.pageCount`.

- [ ] **Step 1: Write failing context, history, and chrome tests**

Assert routed EPUB entry uses only the context request, frame taps toggle page-first chrome, the visual corner toggle/pill are absent, and crossing a spine boundary or item boundary uses router `replace`.

- [ ] **Step 2: Write failing locator restoration tests**

Give the item a locator with `locations.progression=0.5`, install a synthetic same-origin frame document, and assert load restores half its scroll range. With four positions and two analyzed pages, assert the third position writes page 2 rather than page 3. Assert scrolling updates progression/totalProgression, last-resource bottom writes `completed=true`, and earlier positions write false.

- [ ] **Step 3: Verify the EPUB tests fail**

Run `NODE_OPTIONS=--no-experimental-webstorage npm test -- tests/EpubReader.test.js` and confirm the missing frame binding/context behaviour is the failure.

- [ ] **Step 4: Implement frame lifecycle and adjacent navigation**

Attach/detach parent-owned frame handlers on every load and teardown. Keep `sandbox="allow-same-origin"` with no scripts or top navigation. Restore before publishing scroll progress and debounce writes through the existing progress path.

- [ ] **Step 5: Make typography controls effective**

Inject a parent-owned style element into the same-origin frame document using persisted font-size, line-height, margin, width, and theme values. Add controls only for values the injected stylesheet actually applies.

- [ ] **Step 6: Verify and commit**

Run the EPUB tests, full web tests, and build. Commit as `fix: preserve epub reading position`.

### Task 5: Single-owner search, complete series paging, and home restoration

**Files:**
- Create: `web/src/reader/homeState.js`
- Modify: `web/src/lib/api/catalogSearch.js`
- Modify: `web/src/reader/Home.svelte`
- Modify: `web/src/reader/AdvancedSearch.svelte`
- Modify: `web/src/reader/SeriesScreen.svelte`
- Modify: `web/tests/catalogSearch.test.js`
- Modify: `web/tests/ReaderHome.test.js`
- Modify: `web/tests/ReaderSearch.test.js`
- Modify: `web/tests/SeriesScreen.test.js`

**Interfaces:**
- Produces: optional `libraries` input for AdvancedSearch and abortable `readSeriesFilterChoices({ signal })`.
- Produces: session-scoped `{ libraryId, page, scrollY }` home state.
- Produces: shared Pager navigation for series items.

- [ ] **Step 1: Write failing single-owner search tests**

Assert one debounced quick request while advanced is closed, one advanced request while open, no hidden quick refresh on catalogue events, and opening advanced aborts the outstanding quick request. Assert the quick surface shows `aria-busy`/loading immediately during debounce.

- [ ] **Step 2: Write failing advanced lifecycle tests**

Assert passed-in libraries cause no duplicate library request, teardown aborts facet requests, and changing the query from a nonzero page emits only page-zero search.

- [ ] **Step 3: Implement one search owner**

Route typed and event-triggered work to the visible surface only. Normalize page before constructing request criteria and pass one controller signal through every facet request.

- [ ] **Step 4: Write failing series paging and home-state tests**

Return a 101-item total with a first page and assert the next control requests page 1. Remount Home with saved page/scroll, assert that page is requested before render restoration, and assert changing library clears page/scroll to zero.

- [ ] **Step 5: Implement paging and restoration**

Use `Pager` in SeriesScreen. Save state on teardown and page changes, scope it by user/library, restore only after the requested page renders, and do not persist search results.

- [ ] **Step 6: Verify and commit**

Run the four focused test files, full web tests, and build. Commit as `fix: make catalog navigation deterministic`.

### Task 6: Production SSE disconnect normalization and release truth

**Files:**
- Modify: `server/app/src/main/kotlin/io/xoboro/server/Application.kt`
- Modify: `server/app/src/test/kotlin/io/xoboro/server/ApplicationTest.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeEventRoutes.kt`
- Modify: `server/api/src/test/kotlin/io/xoboro/server/api/XoboroNativeEventRoutesTest.kt`
- Modify: `docs/feature-coverage.md`
- Modify: `docs/design.md`
- Modify: `docs/performance.md`

**Interfaces:**
- Produces: production exception handling that consumes ordinary closed-write failures before generic 500 logging.
- Preserves: subscription `use`, capacity, replay, authentication, revocation, and reconnect semantics.

- [ ] **Step 1: Write a failing production-pipeline disconnect test**

Exercise the real application StatusPages configuration with a response channel that closes during an SSE write. Assert the generic `Unhandled request failure` logger is not called and no synthetic 500 body is attempted.

- [ ] **Step 2: Verify the test fails against current production wiring**

Run the focused application test and confirm the generic Throwable handler receives the channel-close exception.

- [ ] **Step 3: Normalize only closed-channel failures**

Catch `ClosedWriteChannelException` at the send boundary while retaining the existing `ChannelWriteException` branch, then install specific handlers for both before the generic production handler. Do not swallow arbitrary cancellation, serialization, authentication, or event-hub failures.

- [ ] **Step 4: Update auditable docs**

Remove the stale popup description and dead copy, describe explicit completion, context ownership, history replacement, search ownership, real-browser acceptance, and record reproducible before/after request counts and timings only where measured.

- [ ] **Step 5: Verify backend and commit**

Run the focused event/application tests and `./gradlew check`. Commit as `fix: normalize reader event disconnects`.

### Task 7: Whole-branch verification, review, PR, and deployment

**Files:**
- Modify only files required by verification findings.
- Update `docs/performance.md` only with measurements actually taken.

**Interfaces:**
- Consumes: Tasks 1–6 as one deployable production image.
- Produces: reviewed PR, green CI/GHCR, and a healthy macmini service running the merged image.

- [ ] **Step 1: Run repository gates**

Run `NODE_OPTIONS=--no-experimental-webstorage npm test`, `npm run build`, `./gradlew check`, and `git diff --check`. Treat warnings separately from failures and report pre-existing warnings honestly.

- [ ] **Step 2: Run browser acceptance**

Against a synthetic/local fixture or authorized deployed library, verify mobile input focus does not zoom, no horizontal reader scroll appears, initial chrome is hidden, tap toggles it, next/back history is replaced, split-final progress resumes correctly, failed images can retry, home state returns, and EPUB in-resource progress returns.

- [ ] **Step 3: Build and smoke-test Docker**

Build the production Dockerfile, start an isolated container, and check readiness, login/session, media reader context, page `maxWidth`, progress conflict/completion, search, series page 2, and SSE reconnect.

- [ ] **Step 4: Obtain whole-branch review and fix blocking findings**

Review the complete diff against the spec. Every Critical/Important finding must be fixed and re-reviewed before push.

- [ ] **Step 5: Push and open the pull request**

Push `fix/reader-experience-completion`, open a PR into `main`, and wait for required CI and GHCR checks. Do not force-push or merge a red branch.

- [ ] **Step 6: Merge and deploy the immutable image**

After required checks pass, merge through the PR, identify the merged commit/image digest, make the GHCR package publicly readable if it is not already, recreate the macmini service with that image, and verify the running digest and health.

- [ ] **Step 7: Post-deploy acceptance**

Repeat the critical reader/search/SSE smoke checks and capture container CPU/memory plus first-context/first-page timing. Roll back to the prior immutable digest if a critical workflow fails.
