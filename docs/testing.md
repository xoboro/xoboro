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

## Fixture policy

- Fixtures are synthetic and reproducible.
- Tests write only to isolated temporary directories.
- No personal names, credentials, library paths, scraped descriptions, covers,
  or copyrighted media are committed.
- Real publication, series, episode, and character names are prohibited even
  when only used as labels; catalog fixtures use explicit synthetic names.
- Archive generators produce the smallest content necessary for the behavior.
- Golden payloads are reviewed for private or environment-specific values.

## Completion rule

Code is not considered complete when only the happy path passes. Tests cover:

- authorization and content restrictions;
- invalid and boundary inputs;
- unavailable and changing storage;
- cancellation, restart, and idempotency;
- concurrent access where applicable;
- serialization and error compatibility;
- migration from the supported Komga baseline.
