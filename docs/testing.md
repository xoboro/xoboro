# Testing policy

Every production behavior must be introduced with automated tests in the same
change.

## Test layers

1. `core` unit tests for platform-neutral rules and invariants.
2. service tests for application use cases.
3. repository tests against a temporary SQLite database.
4. media tests using generated temporary archives and images.
5. API contract tests using Ktor's in-process test host.
6. Komga golden tests against the frozen 1.25.0 contract.
7. protocol interoperability tests for OPDS, Kobo, and KOReader.
8. migration tests using sanitized generated Komga-compatible databases.
9. performance regressions for scanning, analysis, and page delivery.

Run the live anonymous Komga differential suite against isolated servers with:

```shell
./gradlew :compatibility:komga-differential:run --args="\
--reference http://127.0.0.1:25610 \
--candidate http://127.0.0.1:25611 \
--suite compatibility/komga-differential/suites/komga-1.25.0-anonymous.json"
```

Authenticated suites receive authorization through
`KOMGA_DIFFERENTIAL_AUTHORIZATION`, or the reference/candidate-specific
`KOMGA_REFERENCE_AUTHORIZATION` and `XOBORO_CANDIDATE_AUTHORIZATION`
environment variables. Never put credentials in suite files or CLI arguments.
The differential workflow also reuses the independent validators returned by
each server and requires authenticated media requests to return `304`.

## Fixture policy

- Fixtures are synthetic and reproducible.
- Tests write only to isolated temporary directories.
- No personal names, credentials, library paths, scraped descriptions, covers,
  or copyrighted media are committed.
- Real publication, series, episode, and character names are prohibited even
  when only used as labels; catalog fixtures use explicit synthetic names.
- Archive generators produce the smallest content necessary for the behavior.
- Golden payloads are reviewed for private or environment-specific values.

## An assertion has to be able to fail

A new assertion is not trusted until it has been seen to fail. Break the behaviour
it names, run it, watch it go red, put the behaviour back. It costs a minute and it
is the only thing that distinguishes a test from a comment.

This is policy because vacuous assertions have repeatedly survived review here, and
none of them looked wrong:

- A fixture in the wrong shape. Duplicate-page tests mocked bare arrays where the
  routes answer a page envelope, so they asserted the honesty of a screen that
  rendered nothing at all in production. Fixtures have to be the shape the server
  actually sends.
- One `mockResolvedValue` answering every call. A test claiming a count came from
  the server could not tell a re-read from a stale snapshot, and passed either way.
  Where a test distinguishes two reads, the two replies must differ.
- A DOM node captured before the assertion. Reading `textContent` from an element a
  re-render has already replaced asserts against frozen text, which contains
  whatever the old state had. Wait for the new state, then query.
- Comparing rendered content to test something that does not affect it. Keying an
  `{#each}` changes node identity and nothing else, so an assertion about a row's
  text passes under any key.
- **A fixture that throws to fail the test, called from code that catches everything.**
  `runCatching { ... }.getOrNull()` catches `Throwable`, so a stub written as
  `throw AssertionError("this must not be called")` is swallowed and the test passes *while
  doing the thing it forbids*. Found in `ComicInfoMetadataProviderTest`: the stub refused to
  materialize an archive, `readComicInfo` swallowed the refusal, and a mutation that made every
  archive get fetched left the suite green. **Count the forbidden call and assert the count is
  zero** — a counter cannot be caught. Kotlin's `runCatching` and bare `catch (_: Exception)`
  around a callback are both enough to hide it.
- A shell check whose pattern matches nothing. A regex that finds no candidates
  compares an empty set and reports success. On macOS `grep -P` is unsupported and
  fails silently when stderr is discarded, which is indistinguishable from "no
  matches". Put content checks in the suite, not in a one-liner.
- The same trap inside a test: a loop over elements a selector did not find, or a
  parse that produced no pairs, asserts nothing. Assert the match count before
  asserting anything about the matches.
- An assertion inside `waitFor` that is **already true**. `waitFor` retries until its
  callback succeeds, so if the property holds before the change lands it succeeds on
  the first attempt and reports nothing about the state afterwards. Wait for a signal
  that the change has happened — a row gone, a value replaced — and then assert
  synchronously. Do not put the settle and the assertion in one `waitFor`.
- Asserting through a component's own happy path. A shared safety component needs
  tests for the inputs its current callers never send — that is where its holes are.
- A value asserted against a fake that accepts anything. Every discovery feed answered
  `500 Unsupported catalog sort property` while a route test asserted the exact sort the
  route passed on: the fake catalog records whatever property it is given, and only the
  SQL layer rejects an unknown one. A test that pins what a component *sends* needs a
  receiver that can refuse, or a companion test that reaches the real one.
- A constant asserted against its own literal. `assertEquals("createdAt", FEED.sortProperty)`
  is a restatement, not a check — it passed for all five feed definitions while all of
  them were broken. Worth keeping when the value is a client-facing contract, but say in
  place what does verify it.
- A discriminator that does not discriminate. An ordering assertion over two rows sorted
  `alpha`, `beta` cannot tell "newest first" from "by title, descending": both name the
  same row. Replacing the sort field with `title` survived. Three rows with the answer in
  the middle rule out every other ordering the query layer offers.
- A number matched as a substring. `toContain('5')` for a 5 MB size was satisfied by the
  "2025" in the date beside it, and passed with the size at zero and with the field
  missing. Match the figure with its unit, anchored.
- A field name searched for in the whole document. Checking that a schema describes
  `items` by searching the component for the word found it in the `required: [...]` line,
  so deleting every property left it passing. Match the key at its own indentation, inside
  the block that is supposed to declare it.
- **Every case in a class built from one request helper that fills in every field.**
  `XoboroNativeProgressTest` has eighteen cases and all of them sent a body from one
  `validRequest()` that always supplies a `locator`. `locator` was a required field, so
  **every read-progress write from the comic reader answered `400`** — a comic has no spine
  and no `href`, so its reader sends a page alone — and eighteen passing tests said nothing
  about it, because not one of them sent the shape that reader actually sends. A shared
  helper is worth having; what it must not be is the *only* body the class ever sends. Add
  at least one case per optional field, spelling the body on the wire rather than
  constructing the DTO: building the request object with `locator = null` proves Kotlin
  accepts a null, not that a client omitting the key is accepted. Deserialization refuses a
  missing field before any code you wrote runs, so only the wire form reaches the defect.
- A client-side fake that agrees with the documentation instead of the server. The comic
  reader's test fake answered `204` for a progress write because the OpenAPI description
  said `204`; the server answers `200` with a body. A fake copied from a description
  inherits whatever that description is wrong about, and a description with no
  `requestBody` at all — which is what this endpoint had — cannot be inherited from at
  all. Derive a fake from a real response, and keep the drift test's limits in mind: this
  repository's OpenAPI check compares `(method, path)` pairs and says nothing about bodies
  or status codes.

Two related traps in reading results rather than writing them: **check the exit
code, not the summary line** — this suite once printed "224 passed" while exiting `1`
on unhandled errors outside any assertion — and remember that jsdom and file readers
render a NUL byte as a space, so byte-level questions need a byte-level check.

Where an assertion is known to be weaker than it looks, say so in the test. One case
here documents that removing the guard it guards does not fail it, and what variant
it does catch.

## Checking the web UI against a real server

The web suite mocks `fetch`. That is the right default — it is fast and it isolates
component behaviour — but it cannot find a wrong belief about a response, because the
mock encodes the same belief as the code. The duplicate-pages screen rendered nothing
in production while every one of its tests passed, for exactly that reason.

So before a UI change lands, run it against the real thing:

```shell
npm --prefix web run build
./gradlew :server:app:installDist

# The password file has to exist before the server reads it, or start-up fails with
# "XOBORO_INITIAL_ADMIN_PASSWORD_FILE could not be read". Generated, never committed,
# and never passed on a command line where it would land in shell history.
mkdir -p /tmp/xoboro-check/backups
python3 -c "import secrets; print(secrets.token_urlsafe(24))" > /tmp/xoboro-check/pw

XOBORO_PORT=25699 \
XOBORO_DATABASE_PATH=/tmp/xoboro-check/db.sqlite \
XOBORO_BACKUPS_PATH=/tmp/xoboro-check/backups \
XOBORO_WEB_PATH="$PWD/web/dist" \
XOBORO_INITIAL_ADMIN_EMAIL=check@example.test \
XOBORO_INITIAL_ADMIN_PASSWORD_FILE=/tmp/xoboro-check/pw \
  server/app/build/install/app/bin/app
```

Delete `/tmp/xoboro-check` afterwards. Authenticate with
`POST /api/xoboro/v1/session` carrying `{"email":…,"password":…,"transport":"COOKIE"}`
and a same-origin `Origin` header — without one the CSRF guard refuses the mutation,
which is itself worth confirming.

What this catches that the mocked suite cannot, all of it found this way at least once:

- **Response shape.** Ask each listing endpoint the console uses whether it answers a
  bare array or the `items`/`totalItems` envelope, and compare against what the client
  assumes. `web/tests/pageEnvelope.test.js` keeps the two lists in agreement afterwards,
  but the first census has to come from a live server.
- **Request shape.** A field the form sends in a form the server rejects. The library
  location is a `file:` URI and was labelled "Path"; nothing in a mocked test objects,
  because the mock accepts anything.
- **Errors the client cannot attribute.** A `400` whose body carries no `field` cannot
  drive a field-level error, so a form keyed on one shows a generic notice instead. Only
  the real error body reveals that.
- **Routing precedence.** Whether an unmatched path under a protocol prefix answers with
  the application shell. Probe `/koreader/x`, `/actuator/x`, `/kobo/x`, `/api/...` and
  confirm none returns HTML.
- **Malformed input.** Percent-encodings that decode to something no filesystem accepts.
  Watch the server log for `"level":"ERROR"` while probing; an uncaught throw shows up
  there as a stack trace per request even when the status looks reasonable.
- **Provenance guards.** That a cookie-authenticated mutation is refused with a foreign
  `Origin` **and** with none at all. Failing open on a missing header is the easy mistake
  and a mocked test never sends real headers.
- **Content negotiation.** Whether the status a client needs actually reaches it. A
  browser's `EventSource` sends exactly `Accept: text/event-stream` and cannot be told to
  send anything else, so the JSON error body could not be negotiated and every
  unauthenticated subscription to the event stream answered `406` instead of the `401` the
  description declares — and `EventSource` retries on its own forever, so an expired
  session left the UI silently disconnected. Note that Ktor's **test** client cannot pose
  this question through the usual helper: its ContentNegotiation plugin appends
  `Accept: application/json`, so asking for one type sends two and negotiation succeeds.
  Use a client without that plugin, or `curl`.

Some defects are only visible with real layout, which jsdom does not have. Measuring the
DOM in an actual browser — Playwright, or the browser console — is the only way to reach
them, and an assertion about them in the mocked suite would pass with the bug present:

- **Scroll and snap geometry.** A shelf's first cover was clipped at the window edge on a
  real library. `scroll-snap-align: start` aligns to the scrollport, which is inside the
  padding, so a shelf that snapped came to rest at `scrollLeft: 16` and ate its own gutter;
  `scroll-padding-inline` is what tells snapping about it. `proximity` made it intermittent
  — two shelves rested at 0 and a third at 16 — so it read as a rendering glitch rather than
  a rule. Diagnosed by reading `scrollLeft`, `paddingLeft` and `getBoundingClientRect()` off
  the live page, and verified the same way; in jsdom `scrollLeft` is always 0.
- **Anything else that needs layout**: overflow at a narrow viewport, an element covering a
  control, sticky positioning, and focus order that depends on painted position. Record such
  a finding in the component with a note that the suite does not cover it and why, rather
  than writing an assertion that cannot fail.

## Two lists agreeing is not a check

Where a rule is written down twice by hand — a description and a client, a reserved list
and a probe list — a test comparing them passes on whatever both of them leave out. This
has happened three times here, and each time the guard was working exactly as designed:

- `web/tests/pageEnvelope.test.js` compared a list of paged endpoints against the OpenAPI
  description while nine endpoints answered the envelope and were documented as
  `{ type: object }`.
- `XoboroWebAssetApplicationTest` compared its probe list against the reserved prefix list
  while `/sse`, `/oauth2/authorization`, `/login/oauth2/code` and `/v3/api-docs` were in
  neither, and answered the application shell.
- The discovery feeds' sort fields were pinned by a test that asserted them against
  themselves.

The fix in each case was to make something that is not a list the authority: the routing
tree the server builds, or the responses a running server actually sends.
`XoboroNativePageEnvelopeContractTest` and `reserves every path the server registers` are
both that shape. When a test cannot reach the authority for part of its subject — a path
with a template parameter, a route that needs a fixture — say so in the test, and name
what is still covered by agreement alone.

## A tally of zero failures is not a green build

The verification gate is a full rebuild in an isolated worktree:

```
git worktree add /tmp/gate HEAD
cd /tmp/gate && ./gradlew clean build --continue \
  --no-build-cache --rerun-tasks --no-configuration-cache
```

Read **`BUILD SUCCESSFUL`**, not the test tally. They are different claims, and the gap
between them is where a run gets reported as green while a third of it never happened.

A module whose `compileKotlin` fails writes no test results at all. Its tests do not fail —
they do not exist. So summing `failures` and `errors` across every
`build/test-results/**/*.xml` gives a confident `failed=0` for a build that stopped
compiling partway. Three runs of the same gate, same commit:

| | suites | tests | failed | build |
|---|---|---|---|---|
| default heap | 107 | 623 | 0 | **FAILED** |
| `-Xmx3g` on the command line | 151 | 746 | 0 | **FAILED** |
| heap set in `gradle.properties` | 183 | 884 | 0 | SUCCESSFUL |

All three report zero failures. Only the last one ran the suite. A gate reported as
"759 tests, 0 failed" earlier in this repository's history was one of the truncated ones.

The cause was the Kotlin compile daemon's heap, which `gradle.properties` did not set. It
matters only on a full rebuild — every module at once, in parallel — which is exactly what
the gate is. It surfaces as

```
BackendException: Backend Internal error: Exception during IR lowering
Could not read class: VirtualFile: .../java/util/regex/Pattern.class
```

with `OutOfMemoryError` several `Caused by` levels down, in files nobody touched. That reads
as a compiler bug, and was diagnosed as one here more than once — including after it
reproduced in an isolated worktree, which was taken as ruling out contention. It does not
rule anything out: an isolated worktree *is* a full recompile, so the control was selecting
for the cause.

So when a build fails with no failing test:

- **Compilation ran out of memory** — `OutOfMemoryError` below an `IR lowering` or
  `Could not read class` line. Check `gradle.properties` still sets `kotlin.daemon.jvmargs`.
- **Concurrent builds shared a build directory** — `NoClassDefFoundError`,
  `initializationError`, `EOFException`, a missing `in-progress-results-generic.bin`, or a
  failing task with zero failing cases. Use a worktree per agent.
- **A real defect** — carries an assertion message, and the XML tally is non-zero.

Only the third is about the code.

## Checking that search finds the right rows, not merely some rows

Every check of this engine had been a row count. `GET /series?query=이` answering 1,523
and `/media-items?query=이` answering 77,360 was read as "search works" — and it does not
say that. It says search answers.

`scripts/search-accuracy.py` asks the discriminating question. It takes a title the
catalogue actually holds, cuts a fragment out of the **interior** of it, asks the API for
that fragment, and checks whether the entity the fragment came from is in the answer. The
interior matters: a prefix is answered by the word index, and only an interior fragment
exercises the trigram index V32 added so a Korean title could be found by a fragment
inside it.

Run it against the host, never against a copy pulled locally:

```shell
ssh <host> 'SAMPLE=25 python3 -' < scripts/search-accuracy.py
```

Titles are the owner's private material. They are read inside SQL, held in memory, and
used only to build a URL and a count query; the output is counts, ratios and — for a
failure — the fragment's *shape* (its length and which scripts it mixes), never its value.

### Recall and rank are different questions

Measured together they make a correct index look broken. Asked separately, over 25 sampled
titles each:

| measurement | found itself | median matches | returned nothing |
| --- | --- | --- | --- |
| series, interior fragment, single token | **25/25** | 1 | 0 |
| series, interior fragment, 8 chars | **25/25** | 1 | 0 |
| media items, interior fragment, 8 chars | **25/25** | 6 | 0 |
| series, leading word | **25/25** | 2 | 0 |
| media items, interior fragment, 3 chars | 18/25 | 133 | 0 |
| media items, leading word | 14/25 | 349 | 1 |
| six nonsense queries | — | 0 | `totalItems=0` for all |

**Found itself** is a membership test: the entity the fragment was cut from is in the
answer, or it is not. That is the metric to read.

The rows below 25/25 are **selectivity, not loss**, and the median column is what shows it
rather than asserting it: every row whose median answer is small finds its entity every
time, and only the rows answering in the hundreds push it past the first page. Three
characters out of 145,105 items is a broad query. Cut the same window at eight characters —
median 6 — and media items go to 25/25 as well. `returned_nothing` stays at zero
throughout, which is what says nothing was lost.

The negative controls are what stop a perfect score from being reachable by answering
"everything" to every query.

**What settles that the trigram index is the thing working**: a word index cannot match a
fragment that starts inside a word, so a non-empty answer to an interior cut can only have
come from the trigram index. A broken or absent one shows up here as `returned_nothing`,
which is zero.

### Two ways this check was wrong before it was right

Both are worth keeping, because both produced a confident number that meant nothing.

**A tokenised index measured against a substring test.** Unrestricted, the same check
scored 84% on series and 68% on items, and an earlier cut said 56% and 20%. Every failure
without exception was a fragment containing a space or a punctuation mark; not one
purely-hangul, latin or CJK fragment failed. A trigram index tokenises on separators, so a
window straddling a word boundary is not one token — the low numbers measured the
measurement.

**A criterion that could not fail.** The first version reported `recall_ok` as
`totalItems >= instr_count`. The trigram index concatenates title, sort title, series
title and alternate titles into one indexed column, so its match set is a *superset* of a
title-only substring test and that inequality is nearly always satisfied whatever the
index does. It is still printed, as `at_least_substring`, but labelled as the lower bound
it is rather than as recall.

**A sample taken from one corner.** `ORDER BY id LIMIT 25` returns the same lowest
identifiers on every run, so any class of title that sorts late — a different importer, a
later scan — was never once checked while the output said 25 titles. Ordering by the *tail*
of the identifier fixes it: these are random hex, so their last characters are uncorrelated
with their first, and the order is still stable between runs.

  The first attempt at that, `ORDER BY hex(id)`, is a no-op — hex-encoding text preserves
  its byte order — and it was caught only because re-running produced the same eight rows
  character for character. A reshuffle that changes nothing looks exactly like a reshuffle.

  Re-measured on a genuinely different sample, the conclusion holds: series 25/25 on all
  three paths, and media items 25/25 with the selective fragment **even though its median
  answer grew from 6 matches to 76**. The rows that move are the broad ones.

  A sample of two is not a measurement either. Restricting an eight-character window to
  single tokens rejects most candidates, and the first run of that variant checked two
  media items and reported 100%. The pool had to be deepened before the number meant
  anything.

## Completion rule