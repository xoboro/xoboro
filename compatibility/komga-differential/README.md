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

The CI workflow also builds two regular two-page synthetic CBZs and one
standalone two-page synthetic CBZ, claims both fresh servers, scans the same
mounted library, and runs
`komga-1.25.0-catalog.json`, `komga-1.25.0-opds-v1.json`, and
`komga-1.25.0-media.json`. The catalog suite
keeps generated identifiers and timestamps out of comparison while checking
library settings, page envelopes, metadata, media analysis, natural numbering,
sibling navigation, one-shot projection, source paths, and alphabetical
groups. The media suite compares page
inventory, manifests, archive and image bytes, content types, lengths, download
names, conversion, thumbnails, OPDS acquisition, and unsupported raw-page
status. The OPDS v1 suite compares canonical Atom/OpenSearch XML across every
feed category, filtering, paging, navigation, acquisition metadata, and
missing-resource status.

Each case defines a method, origin-relative path, optional headers/body, and a
comparison policy. Status and normalized content type are compared by default.
Additional headers are opt-in. JSON and XML are compared structurally, text
uses normalized line endings, and binary content uses SHA-256. XML parsing
rejects DTDs and external entities. `bodyMode: "NONE"`
compares only status and configured headers for error bodies whose text is not
part of the certified contract.

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

`ignoreXmlPaths` and `unorderedXmlPaths` use the same slash-separated wildcard
form. Attribute segments start with `@`:

```json
{
  "ignoreXmlPaths": ["/feed/updated", "/feed/link/@href"],
  "unorderedXmlPaths": ["/feed/entry"]
}
```

For authenticated suites, keep credentials out of files and command history:

- `KOMGA_DIFFERENTIAL_AUTHORIZATION` applies to both servers.
- `KOMGA_REFERENCE_AUTHORIZATION` overrides only the reference.
- `XOBORO_CANDIDATE_AUTHORIZATION` overrides only the candidate.

Cases for independently generated resources can declare complete-segment path
variables:

```json
{
  "path": "/api/v1/books/{BOOK_ID}/file",
  "pathVariables": ["BOOK_ID"]
}
```

Provide each value through `KOMGA_REFERENCE_BOOK_ID` and
`XOBORO_CANDIDATE_BOOK_ID`. Values are restricted to safe URI path-segment
characters; credentials and identifiers never belong in suite files.

Suites and server data must follow the repository's synthetic fixture policy.
