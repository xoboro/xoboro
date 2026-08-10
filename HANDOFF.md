# Xoboro handoff — 2026-08-07

State at handoff: `main` at `6ea31d0`, tagged **v0.3.0**, deployed and verified. Working tree clean.
No open PRs. No open issues (#182 closed).

Deployed: `macmini`, container `xoboro-xoboro-1`, image `xoboro/xoboro:0.3.0`,
rev `6ea31d0ae29e0e8917373197bf75f91d8614309f`.

---

## READ FIRST: two constraints that are easy to break

### 1. The library data is the owner's personal IP

**Source code may be read. Crawler / library output must not be opened.** Print only counts, ratios,
status codes, digests, timings, lengths, byte sizes and JSON **key names** — never titles, summaries,
authors or paths.

**Use key allowlists, never blocklists.** This has already been violated: a filter excluded the `items`
key, but the Komga-compat route answers with `content`, so real titles leaked. A blocklist is one
unexpected key away from a leak.

Patterns that worked: aggregate in SQL and print only the aggregate (`count`, `sum(length(...))`,
`avg_bytes`); to prove interior search worked on real titles, `substr(title, 4, 4)` was matched inside
SQL and only the **hit count** printed (40/40 vs 23/40).

### 2. ~~`TASK_POOL_SIZE` is currently 1~~ — now 2

It had been lowered to end an incident (below) and left there. Raised to 2 on 2026-08-10, which is
what this section recommended: the code default is `availableProcessors.coerceIn(1, 4)` = 4 on this
host, and this host also runs komga, immich, kavita and filestash on 10 cores.

```
PUT /api/xoboro/v1/server-settings  {"taskPoolSize": 2}    # live, resizes the pool in place
```

The queue was empty when it was raised (`SELECT count(*) FROM task` = 0), so nothing started draining
at the new width. Container CPU stayed at 0.5%. **Check the queue before changing this** — raising it
against a backlog starts spending the difference immediately.

---

## Outstanding work, highest value first

### A. ~~Cold scan is quadratic~~ — found and fixed

**Cause: the search-index insert triggers, not reconciliation.** `catalog_search_fts` and
`catalog_title_substring` declare `entity_type`/`entity_id` UNINDEXED, so each trigger's
`DELETE ... WHERE entity_id = NEW.id` planned as a full pass over the index. Inserting one book ran
four of them, giving n × n. `ADR 0052` was the wrong suspect; the reconciliation SQL is fine.

- **V35** drops those deletes. They could not remove anything — the key they searched for was
  created a moment earlier — and the index built without them is identical row for row.
- **V36** gives updates and deletes a `catalog_search_key` rowid to address, so they seek instead of
  scanning. This is where V31 left off: V31 stopped the update trigger firing needlessly, not the
  cost when it does fire.

`cold_scan` at 15,050 items: **102,738 ms → 1,969 ms**, and sub-linear now (4.21× the time for
4.93× the items). Analysis and rescan unchanged.

**Upgrade cost, measured on snapshots of the deployed 4.55 GB database** (148,444 entities): V35
takes 12 ms, V36 takes **26.6 s** of statement time, one-off at startup — down from 52.2 s once the
key table adopted the word index's existing rowids instead of rebuilding it under new ones. Both
digests came back unchanged, every index row sits on the rowid its key names, no entity is without a
key, `integrity_check` returned `ok`. Snapshots were taken with `.backup` against the live database
and deleted afterwards; the container was never stopped.

The remaining 26.6 s is 17.7 s rebuilding the interior-match index, 5.1 s adopting, 1.6 s emptying.
Removing the rebuild too needs a rowid per index rather than per entity — `AUTOINCREMENT`, a
sentinel row, and a trigger on the key table. Deliberately not done: permanent schema complexity for
a one-off 20 s. `docs/performance.md` carries the full reasoning.

Full numbers and reasoning: `docs/performance.md`, section **"Settled: the cold scan was quadratic
because every insert read the whole search index"**. Wiki `xoboro-cold-scan-is-quadratic` still
describes only the symptom.

Before and after, one run per cell rather than the earlier three-repetition averages, so only the
scan's 52x is outside the ±25% a single cold metric moves by:

| metric | 3,050 before | 3,050 after | 15,050 before | 15,050 after |
|---|---|---|---|---|
| `cold_scan.wall` | 4,404 ms | **468 ms** | 102,738 ms | **1,969 ms** |
| `cold_analyze.wall` | 7,953 ms | 6,211 ms | 34,575 ms | 33,144 ms |
| `unchanged_rescan.p50` | 104.6 ms | 97.0 ms | 625.1 ms | 605.5 ms |

Reproduce either state with synthetic fixtures, locally, **zero load on the deployed host**:

```bash
scripts/cold-scan-repetitions.sh 3 300 10 50    # 3,050 items, ~1 min/rep
scripts/cold-scan-repetitions.sh 3 1500 10 50   # 15,050 items, ~2 min/rep
```

**What is still a full pass:** renaming a series rewrites its books' rows as one `rowid IN (...)`.
A trigger cannot loop, so that stays one pass — unchanged from before, and the only remaining one.

**Two traps this left behind.**

The mutation that checks the metadata rebuild path uses the key's rowid did not bite at first: with
one book in the fixture, SQLite hands an omitted rowid `max(rowid) + 1`, which is the number the
rebuild's own delete just freed, so the wrong code landed on the right number by accident. The test
now indexes three books and rebuilds one that is not the last. Any future assertion about rowid
identity needs the same care.

**`date +%s%3N` inside `alpine:3` silently drops the `%3N` and returns whole seconds.** It does not
error - it prints a plausible number - so a migration measured that way reported "57 ms" for work
that took 57 seconds, and the difference was only visible because the number was too good to be
true. Use `sqlite3`'s own `.timer on` and sum the reported `real` values; busybox is not GNU
coreutils.

### B. ~~Record the measurement in `docs/performance.md`~~ — done

`docs/performance.md`, section **"Open, and now localised: the cold scan is near-quadratic"**, carries
the full table, the reasoning that excludes the fixed-cost hypothesis, and the note that this file's own
"listing is not the difference" conclusion does not generalise to local sources. **Read it there** — it
is the tracked copy.

### C. ~~A sidecar-only cover regeneration task~~ — done

The gap that caused the incident: there was **no way to ask for "covers only"**, so regenerating 3,339
series covers meant a library metadata refresh, which also queued all 145,105 books.

```
POST /api/xoboro/v1/libraries/{libraryId}/series-metadata-refresh
```

A series' cover comes from its sidecar and is rewritten by a series refresh, so scoping the existing
fan-out to its series stage is the whole fix — 3,339 tasks instead of ~148,000. A separate route
rather than a flag on the existing one: the two differ by two orders of magnitude in what they queue,
and a query parameter that quietly decides which you get is how the expensive one was reached by
someone who only wanted covers. The series-only fan-out also carries its own task id, or the queue
would deduplicate it into a whole-library refresh that was already pending.

**Not verified against production.** No series-metadata refresh has been run on the deployed host.
Check the fan-out before you do — `SELECT count(*) FROM task WHERE state IN ('PENDING','RUNNING')`
should be near zero first, and it should reach 3,339 and not six figures.

### D. Finish wiring the change feed

Shipped in 0.3.0 but partial. Wiki: `xoboro-catalog-change-feed`.

1. **Only reconciliation appends.** `MetadataEditing` and `MetadataRefreshLifecycle` also publish
   `CatalogMutationEvent` and do not append, so a client sees a metadata edit over SSE while connected
   and misses it while away. Both own transactions — the fix is the same
   `transaction.appendCatalogChanges(events, now)` call.
2. **No retention sweep is scheduled.** `sweepThrough` exists and nothing calls it, so the floor stays 0.
   Harmless now, unbounded growth later.

### E. Smaller, independent

- **`-Xmx` is unset in the distribution, and that is not worth changing in code.** Investigated
  2026-08-10 and closed without a change. The deployed container does not have the problem: it sees
  **7.818 GiB**, not the host's 48 GB, so the JVM's 25% default is about 1.95 GB of *maximum* heap
  against 325 MiB actually resident. The 12 GB figure is a host install reading the whole box. Max
  heap is a ceiling, not a reservation, and a fixed `-Xmx` in the image would be wrong in the other
  direction on a small machine — too high to fail cleanly, so it swaps instead of throwing. The start
  script already honours `JAVA_OPTS` and `APP_OPTS` (`/opt/xoboro/bin/app` line 244), so a host
  install that wants a ceiling sets one without a release. **Reopen this only with a measured live
  set that a 25% ceiling actually constrains.**
- **The media-item listing sorts the whole catalogue to return one page.** Localised 2026-08-10,
  not fixed. Its default order is `sm.title_sort` — a column two joins away on the series'
  metadata — so `EXPLAIN QUERY PLAN` shows `SCAN b` plus `USE TEMP B-TREE FOR ORDER BY`: every
  book scanned, joined to three tables and sorted, to return ten. Fetching ten ids costs 13.5 ms
  at 15,050 items, as much as counting all of them. Fixing it means denormalising the sort key
  onto `book` and maintaining it when a series is renamed — a migration plus triggers — or
  changing what the listing is sorted by, which is a product decision. Full numbers and the plan
  output are in `docs/performance.md`.
- **CI cannot publish to Docker Hub.** Needs a `DOCKERHUB_REPOSITORY` variable plus
  `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` secrets, or make the GHCR package pullable from macmini.
  Today every Docker Hub push is manual from the MacBook.
- **Genre facet abbreviations are not a defect.** `17`, `adv`, `bl`, `ts`, `vrmmo` are what the source
  calls them: of 145 distinct genres, 95 are <=3 chars, and **zero** appear in the tag table. Improving
  the display needs a curated per-source dictionary — a product decision, not a bug.

---

## What shipped this session

Tag -> commit verified against `git log -1 <tag>`; a tag covers every PR merged since the previous one.

| version | PRs | change |
|---|---|---|
| 0.1.10 | #184, #185 | reach a catalogue larger than one page from the reader home; compact facet filter — chips, cap of 12, find box past 16 |
| 0.1.11 | #186, #187 | Korean interior search (second trigram index over titles, ORed); results-first search layout |
| 0.2.0 | #188 | covers stored at display size, `source_name`, 5-minute artwork cache — **breaking** |
| 0.2.1 | #189 | drop facets that cannot narrow, one paging nav instead of two, segmented scope control |
| 0.3.0 | #190 | durable catalogue change feed, `GET /changes` |

### Measured outcomes

- **Korean interior search:** 40 fragments from real titles — old prefix index found 23/40, new index
  40/40. Index 53 MiB, migration 1.08 s over 148,444 rows.
- **Cover bytes on the wire:** 100 series covers **8.4 MB -> 1.7 MiB** (measured: 1,785,462 bytes, avg
  17 KB). Stored width 1600px -> 300px on all 3,338 sidecar-covered series; sidecar storage
  273 MiB -> 58 MiB; `source_name` recorded for 3,338/3,338. This was the cause of the slow
  back-navigation to Home that had been unexplained.
- **Read latency (earlier in the arc):** series listing 16.5 s -> 0.18 s once the read path stopped
  writing.

---

## The incident, so it is not repeated

Regenerating covers required refreshing 3,339 series. The only trigger available fans out to every book
as well. On the 10-core host:

```
xoboro-xoboro-1  cpu=758%   loadavg 40.5 / 10 cores
REFRESH_BOOK_METADATA  PENDING 1814  RUNNING 8
```

Book metadata refresh is the most expensive per-item operation in the system — it re-reads the file and
rebuilds the full-text row, evaluating a view with three joins and five correlated `group_concat`
subqueries. V31 measured that path at **43 minutes of CPU across 111,745 books**. `TASK_POOL_SIZE` was 8.

It was **not** lock contention — zero `SQLITE_BUSY` in the logs; the CPU was real work.

Recovery: `taskPoolSize -> 1`, then `DELETE /api/xoboro/v1/tasks/unclaimed` (cleared 2,191 PENDING;
leaves RUNNING and DEAD alone), then let RUNNING drain. **758% -> 0.30%.** Nothing needed was lost — the
cover work had already completed.

**Check a task's fan-out before running it against production.** See item C.

---

## Environment notes

- Native API is **outside** the context path: `http://host:25610/api/xoboro/v1/...`. Only the UI is under
  `/xoboro/`. Probing `/xoboro/api/...` returns 404.
- Login: `POST /api/xoboro/v1/session` with `{"email","password","transport":"BEARER"}`. The token field
  is **`accessToken`**, not `token`. Default `transport` is `COOKIE`, which returns no bearer token.
- Admin password: `docker exec xoboro-xoboro-1 cat /config/initial-admin-password` — into a variable,
  never echoed.
- Deploy: `/Users/rilacc/app/xoboro-deploy` on `macmini`, `docker compose pull && docker compose up -d`,
  docker at `/Users/rilacc/.orbstack/bin/docker`. Verify with the container's
  `org.opencontainers.image.version` / `.revision` labels.
- Inspecting the deployed DB without a sqlite3 in the app image:

  ```bash
  ssh macmini '/Users/rilacc/.orbstack/bin/docker run --rm -i -v xoboro_xoboro-config:/c alpine:3 \
    sh -lc "apk add --no-cache sqlite >/dev/null 2>&1; sqlite3 /c/xoboro.sqlite"' <<'SQL'
  SELECT count(*) FROM task WHERE state IN ('PENDING','RUNNING');
  SQL
  ```

  Heredoc on stdin, not SQL as an argument — quoting does not survive ssh -> docker -> sh -> sqlite3.
  Verify the **first** output line before trusting a long-running loop.
- CI is **`workflow_dispatch` only**: `gh workflow run CI --ref <branch>`. No checks appear otherwise, so
  a PR will look unverified.
- Docker Hub `--push` intermittently fails with `can't assign requested address` on token fetch. Retry;
  it is a network fault, not a build fault.

---

## Verification discipline

The gate is `./gradlew build --continue`. **A reported exit code is not authority** — a piped run reports
the pipe's status, and an unpiped backgrounded run has reported exit 0 with `FAILED` in its log:

```bash
./gradlew build --continue > gate.log 2>&1; echo "exit=$?"
grep -cE "FAILED" gate.log      # this is the answer
```

**And that gate is still not enough after an interface changes.** Adding a method to
`LibraryMaintenanceRequester` broke an anonymous implementation in
`compatibility/komga-api`'s tests. A full `./gradlew build --continue` compiled that module,
reported exit 0 and zero `FAILED`, and CI failed on the same commit: Kotlin's incremental
compilation did not recompile the implementing class against the changed interface, and a clean
checkout has nothing to be incremental about. `--rerun-tasks` reproduces it locally in one go.
**Run the gate with `--rerun-tasks` whenever a change adds to an interface**, or CI is the first
thing that will notice.

`docs/testing.md`: **an assertion is not trusted until it has been seen to fail.** Every behavioural
claim in the PRs above was mutation-verified. Restore a mutation by **editing the line back**, never
`git checkout`. Re-run the unmutated suite between mutations — a restore that silently did not apply
made one mutation look far more load-bearing than it was.

A mutation that fails nothing is information. Two this session: the 3-character trigram floor (a skipped
index probe, not correctness — FTS5 answers a short term with no rows rather than erroring) and
`Pager`'s `summaryKey` (unpinned until an assertion was added stating why it exists).

---

## Wiki — local only, does NOT travel with the repo

`.omc/` is gitignored (`.gitignore:32`), so all 36 wiki pages live on the machine that wrote them and a
fresh clone will not have them. **This document plus `docs/performance.md` are the parts that transfer.**
Everything load-bearing has been repeated here deliberately for that reason.

If the wiki should travel, that is a `.gitignore` decision for the owner to make — un-ignoring
`.omc/wiki/` would put 36 pages under version control, which is a policy change rather than a fix.

The pages, if you do have the working copy:

| page | what |
|---|---|
| `xoboro-cold-scan-is-quadratic` | item A in full, with all numbers |
| `xoboro-cover-sizing-incident` | the defect, the fix, and the incident |
| `xoboro-catalog-change-feed` | 0.3.0 design and what is unwired |
| `xoboro-korean-interior-search` | 0.1.11 design and its honest limit |
| `xoboro-local-first-client-plan` | mobile app plan, sizing, and the one unanswered product question |
| `xoboro-working-agreement` | constraints, restated for handoff |
| `xoboro-measurement-traps-2026-08` | every trap that produced a wrong statement |

## Client scope: books first, Jellyfin deliberately deferred

**Decided 2026-08-07 by the owner: books and comics first. Video/audio is not in scope now, and how it
arrives is not being decided yet. Build so the decision stays cheap — do not build for it.**

The three shapes that were on the table, kept here so nobody re-derives them:

| | shape | adapters in the client |
|---|---|---|
| A | app -> Xoboro server **and** app -> someone else's Jellyfin | 2 |
| B | app -> Xoboro server, which scans video files itself | 1 |
| C | app -> Xoboro server -> **Jellyfin as a source adapter** (`server/sources/jellyfin`, beside `local` and `webdav`) | 1 |

B and C are identical from the client's side. C is how an existing Jellyfin install gets reused without
re-scanning, and `server/sources/{local,webdav}` already establishes that plug point keyed on `sourceId`.
A is the only shape that costs the client anything, and it buys exactly one thing: the app working for
someone who runs Jellyfin and no Xoboro. That is a distribution question, not a technical one.

Why A is expensive, in case it is ever reconsidered: two servers each return their own page 1 under their
own sort, so a correct combined page 3 cannot be constructed — different totals, different sort keys. That
one is not fixable with effort. Progress models also differ (page locator vs playback ticks with
`PlaybackStart/Progress/Stopped` reporting), and the change feed would need two cursors, two floors and
two `resyncRequired` signals, duplicated per device.

### What "stay flexible" means concretely — and what it does not

**Make the schema wide. Keep the code narrow.** A local-store schema change on a device already in the
field costs a migration; adding an adapter later is just new code. So spend the flexibility on the data
model and nowhere else.

Do:

- shape the client's local store on **`MediaItem`**, not on comics — ADR 0023 already made that the root
  server-side (`Library -> MediaItem -> {Comic, Novel, Book, Video, Audio}`), so mirroring it means a
  video row fits later without a device migration
- keep **progress as two kinds from the start**: a page/locator position and a timeline position. ADR 0023
  already refuses to force both into one nullable record; the client should not undo that
- let the sync layer carry the **server identity** it is talking to, even with exactly one, so a second
  upstream is a row rather than a rewrite
- keep capability checks (`page sequence`, `timeline`, ...) rather than switching on file extension —
  again, the server's existing model

Do not:

- build an adapter interface with one implementation. `RULES.md` and `PRINCIPLES.md` in this repo both say
  avoid unnecessary abstractions and no speculative features; a one-implementation port is exactly that
- write a Jellyfin client, DTOs or auth flow
- add `Video`/`Audio` UI

**In short: the local schema should be able to hold a video someday; nothing in the code should mention
one.**
