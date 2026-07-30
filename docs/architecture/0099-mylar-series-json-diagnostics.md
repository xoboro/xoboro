# 0099 — Mylar `series.json` schema and diagnostics

## Status

Accepted.

## Context

The coverage row asked for "Complete supported schema and validation diagnostics". The second half was
the more valuable one, and it was entirely absent: the provider wrapped its whole body in

```kotlin
runCatching { … }.getOrNull()
```

so a `series.json` with a typo, a truncated write, an unexpected `status` value, or an unparsable
`age_rating` behaved **exactly** like a directory with no sidecar at all. Nothing happened and nothing
said so. An operator who had just written the file could not tell "Xoboro ignored my file" from "Xoboro
never looked".

## Decision

### Diagnostics are a closed set reported to a sink

`MylarSeriesDiagnostic` names six outcomes; `MylarSeriesDiagnosticSink` receives them.

A sink rather than a return value, because a diagnostic is not the provider's product. The provider
returns a metadata patch, and reporting must not change whether that patch is produced — it also keeps
`SeriesMetadataProvider` unchanged for the providers with nothing to report.

A closed set rather than a message string, because a caller has to decide how loudly to report each one
and cannot do that from prose. Concretely:

- `SeriesJsonIgnored` (schema drift) logs at **INFO**. A newer Mylar writing fields this version does not
  read is normal, and treating it as a problem would make every Mylar upgrade look like a broken import.
- Everything else logs at **WARNING**: it is a file the operator wrote that Xoboro could not use.

`SeriesJsonMalformed` and `SeriesJsonNotMylar` are separate because the fix differs. A malformed file
needs rewriting; a valid JSON document with no `metadata` object is the wrong kind of file under the
right name.

`SeriesJsonFieldIgnored` does **not** abort the import. One unparsable `age_rating` must not cost the
operator their title, publisher and summary.

A missing sidecar stays silent. Reaching `SeriesJsonUnreadable` means the file exists and could not be
read — a permission or size problem, which is actionable, unlike its absence.

### Drift is compared against read *and* knowingly-unread names

`KNOWN_FIELDS` contains both the fields the provider reads and the ones it deliberately skips. Omitting
the skipped ones would report every well-formed Mylar file as carrying unknown fields on every refresh,
and that noise trains an operator to ignore the diagnostic that matters. A test asserts a well-formed
file produces **no** diagnostics at all.

### Two fields added, five documented as unread

`booktype` and `imprint` now land in `tags`, because neither has a field of its own and both are short
descriptors of the edition — which is what a tag is.

`imprint` is deliberately **not** folded into `publisher`. That field already holds the publisher, and
replacing it with a division of the same publisher would lose information rather than add it.

The rest are named in the class doc as unread, with the reason, rather than left to be rediscovered:

| Field | Why not read |
| --- | --- |
| `comicid` | A Comic Vine identifier. Turning it into a link means hardcoding a URL shape the file never states, and a wrong link is worse than no link. |
| `collects` | Prose about which other issues a collected edition contains — not a property of this series. |
| `publication_run` | A free-text date range; the catalog stores release dates per book. |
| `comic_image` | A cover URL. Artwork comes from disk sidecars and generation, and fetching a remote image during a refresh would make an offline scan depend on the network. |
| `type` | The schema's own discriminator, checked implicitly by requiring `metadata`. |

## Consequences

- A `series.json` the operator got wrong now says so in the log instead of being indistinguishable from
  no file.
- The provider no longer catches `Throwable` around its whole body, so a genuine programming error inside
  it surfaces instead of reading as "no metadata".
- Verified by mutation: restoring the swallow-everything behaviour fails the unreadable-sidecar test, and
  narrowing `KNOWN_FIELDS` to read-only fields fails both the drift test and the
  no-diagnostics-for-a-well-formed-file test.
- The diagnostics are logged, not stored. Surfacing them through the native history surface would be a
  larger change — history rows are keyed to books and series ids, and a sidecar problem belongs to a
  path — and the log is where an operator debugging their own file already looks.
