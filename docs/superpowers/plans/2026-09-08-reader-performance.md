# Reader Performance Implementation Plan

> Execute this plan sequentially in the `fix/reader-performance` worktree. Keep each
> production behavior behind a focused regression test and commit coherent slices.

**Goal:** Make Xoboro's deployed reader respond faster than Komga by removing the
measured request-admission stall, database write amplification, media buffering, and
client request duplication without changing security or catalog correctness.

**Architecture:** Preserve the existing modular-monolith boundaries. Add only small
read projections where a delivery or screen use case currently hydrates the full
catalog. Keep compatibility endpoints unchanged and optimize Xoboro-native routes.

**Stack:** Kotlin/JVM, Ktor, jOOQ/SQLite, Kotlin Multiplatform core, Svelte 5, Vitest,
Vite, Docker/GHCR.

---

## Task 1: Remove reverse-DNS request admission

**Files:**

- Modify: `server/app/src/main/kotlin/io/xoboro/server/TrustedProxyHeaders.kt`
- Modify: `server/app/src/test/kotlin/io/xoboro/server/ApplicationTest.kt`

1. Add a failing application test whose peer-address reader throws when an ordinary
   request has no forwarded headers.
2. Run the targeted application test and confirm the failure comes from eager peer
   lookup.
3. Move peer lookup inside the forwarded-header branch and read `remoteAddress`, not
   `remoteHost`.
4. Add trusted and untrusted raw-address cases and run the targeted tests.
5. Commit as `fix: avoid proxy reverse dns on ordinary requests`.

## Task 2: Amortize browser-session writes

**Files:**

- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/UserSessionLifecycle.kt`
- Modify: `core/application/src/commonTest/kotlin/io/xoboro/core/application/UserSessionLifecycleTest.kt`

1. Add failing tests proving a recently accessed active session does not touch storage,
   the interval boundary does, and an expired stale row still asks the store.
2. Add a validated touch interval with a one-minute default bounded below the inactivity
   timeout.
3. Short-circuit only an unexpired session whose last access is inside the interval;
   preserve the existing authoritative touch path otherwise.
4. Run `:core:application:allTests` and commit as
   `fix: amortize authenticated session writes`.

## Task 3: Short-circuit and stream native media

**Files:**

- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeContentCache.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeDeliveryRoutes.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeArtworkRoutes.kt`
- Modify: `server/api/src/test/kotlin/io/xoboro/server/api/XoboroNativeDeliveryTest.kt`
- Modify: `server/api/src/test/kotlin/io/xoboro/server/api/XoboroNativeArtworkTest.kt`

1. Change page tests to require matching ETag/date responses to open zero content
   streams and successful responses to open/close once.
2. Replace body-derived native validators with weak metadata-derived validators and
   evaluate conditions before `openPage`.
3. Replace eager byte buffering with `respondOutputStream` copying from the existing
   `MediaContentStream`.
4. Split artwork metadata selection from blob loading using the existing artwork
   repository/lifecycle surface where possible; add only the smallest query method if
   the current contract cannot express it.
5. Run native delivery/artwork tests and commit as
   `fix: short circuit native media revalidation`.

## Task 4: Add bounded reader context

**Files:**

- Modify: `core/application/src/commonMain/kotlin/io/xoboro/core/application/CatalogReadModel.kt`
- Modify: `server/persistence/src/main/kotlin/io/xoboro/server/persistence/JooqCatalogReadRepository.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeCatalogRoutes.kt`
- Modify: `server/api/src/main/kotlin/io/xoboro/server/api/XoboroNativeCatalogDtos.kt`
- Modify: matching repository and native catalog tests
- Modify: `docs/api/native-v1.md`

1. Add failing API tests for a reader-context response containing the selected item and
   bounded previous/next navigation.
2. Add a use-case-specific read projection/query that avoids full-series and duplicated
   media hydration.
3. Expose `GET /api/v1/media-items/{id}/reader-context`, preserving existing endpoints.
4. Add authorization/not-found tests and repository query tests.
5. Run catalog API and persistence tests and commit as
   `feat: add bounded reader context api`.

## Task 5: Remove reader and series request waterfalls

**Files:**

- Modify: `web/src/lib/api/catalog.js`
- Modify: `web/src/reader/ReaderRoute.svelte`
- Modify: `web/src/reader/Reader.svelte`
- Modify: `web/src/reader/EpubReader.svelte`
- Modify: `web/src/reader/SeriesScreen.svelte`
- Modify: `web/tests/Reader.test.js`
- Modify: `web/tests/EpubReader.test.js`
- Modify: `web/tests/SeriesScreen.test.js`

1. Add client/component tests proving reader entry issues one context request and child
   readers reuse the item rather than call `readMediaItem` again.
2. Add `AbortSignal` plumbing to reader context, manifests, and child delivery requests.
3. Pass the loaded context into the chosen reader and use its adjacent identifiers.
4. Patch local series state after mutations when the response is sufficient; retain a
   full reload only for membership/order invalidation.
5. Run the focused Vitest files and commit as
   `fix: remove reader request waterfalls`.

## Task 6: Bound home refresh work and reveal feeds progressively

**Files:**

- Modify: `web/src/reader/homeRefreshCoordinator.js`
- Modify: `web/src/reader/Home.svelte`
- Modify: `web/tests/homeRefreshCoordinator.test.js`
- Modify: `web/tests/ReaderHome.test.js`

1. Add failing tests for events arriving while refresh work is in flight: only one
   strongest follow-up may run.
2. Make coordinator callbacks awaitable and keep `running` plus one merged pending
   scope; dispose prevents a follow-up.
3. Assign independent successful home feeds as each settles while retaining generation
   checks and cancellation.
4. Run the focused tests and commit as `fix: bound home refresh work`.

## Task 7: Normalize SSE disconnects and split route chunks

**Files:**

- Modify: native event route/error handling and its focused server tests
- Modify: `web/src/AppShell.svelte`
- Modify: relevant app-routing tests

1. Add a server test for a client closing an event stream and prove the disconnect is
   not translated into a `500`.
2. Catch only the channel-close/cancellation condition at the stream boundary.
3. Convert reader route components to `wrap({ asyncComponent })` imports, keeping the
   wildcard and route props behavior.
4. Build production assets and assert route-specific chunks exist and entry gzip size
   decreases.
5. Commit as `chore: lazy load reader routes`.

## Task 8: Full verification and delivery

**Files:**

- Update: `docs/performance.md` with reproducible before/after measurements
- Update only workflow/Docker files if validation finds a required correction

1. Run `npm test` and `npm run build` in `web`.
2. Run `./gradlew check` at the repository root.
3. Build the production Docker image and run health, authentication, series, artwork,
   reader-context, manifest, first-page, search, and library-switch smoke checks.
4. Measure warmed host and in-container timing on macmini; compare the same user paths
   with Komga and simple-komga and record the method/results.
5. Push `fix/reader-performance`, open a pull request into `main`, wait for CI, address
   failures, and merge only when required checks pass.
6. Confirm the GHCR package is public/readable, deploy the image built from merged
   `main`, recreate the service, and verify deployed image identity and health.
7. Commit measurement documentation as `chore: record reader performance results` if it
   was not already included in a verified implementation commit.
