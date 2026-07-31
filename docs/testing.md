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
- A shell check whose pattern matches nothing. A regex that finds no candidates
  compares an empty set and reports success. On macOS `grep -P` is unsupported and
  fails silently when stderr is discarded, which is indistinguishable from "no
  matches". Put content checks in the suite, not in a one-liner.
- Asserting through a component's own happy path. A shared safety component needs
  tests for the inputs its current callers never send — that is where its holes are.

Two related traps in reading results rather than writing them: **check the exit
code, not the summary line** — this suite once printed "224 passed" while exiting `1`
on unhandled errors outside any assertion — and remember that jsdom and file readers
render a NUL byte as a space, so byte-level questions need a byte-level check.

Where an assertion is known to be weaker than it looks, say so in the test. One case
here documents that removing the guard it guards does not fail it, and what variant
it does catch.

## Completion rule

Code is not considered complete when only the happy path passes. Tests cover:

- authorization and content restrictions;
- invalid and boundary inputs;
- unavailable and changing storage;
- cancellation, restart, and idempotency;
- concurrent access where applicable;
- serialization and error compatibility;
- migration from the supported Komga baseline.
