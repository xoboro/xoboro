# Komga differential runner

This module compares observable HTTP behavior from a live Komga 1.25.0
reference and an Xoboro candidate. It never records or updates responses.

Run the committed anonymous suite:

```shell
./gradlew :compatibility:komga-differential:run --args="\
--reference http://127.0.0.1:25610 \
--candidate http://127.0.0.1:25611 \
--suite compatibility/komga-differential/suites/komga-1.25.0-anonymous.json"
```

The CI workflow also builds a two-page synthetic CBZ, claims both fresh
servers, scans the same mounted library, and runs
`komga-1.25.0-catalog.json`. The catalog suite keeps generated identifiers and
timestamps out of comparison while checking library settings, page envelopes,
metadata, media analysis, natural numbering, source paths, and alphabetical
groups.

Each case defines a method, origin-relative path, optional headers/body, and a
comparison policy. Status and normalized content type are compared by default.
Additional headers are opt-in. JSON is compared structurally, text uses
normalized line endings, and binary content uses SHA-256.

`ignoreJsonPaths` and `unorderedJsonPaths` use JSON Pointer syntax. `*` matches
exactly one object key or array index:

```json
{
  "ignoreJsonPaths": ["/content/*/id"],
  "unorderedJsonPaths": ["/roles"]
}
```

Every exception must be narrow and reviewed. Do not ignore all identifiers,
timestamps, or nested payloads to make a mismatch pass.

For authenticated suites, keep credentials out of files and command history:

- `KOMGA_DIFFERENTIAL_AUTHORIZATION` applies to both servers.
- `KOMGA_REFERENCE_AUTHORIZATION` overrides only the reference.
- `XOBORO_CANDIDATE_AUTHORIZATION` overrides only the candidate.

Suites and server data must follow the repository's synthetic fixture policy.
