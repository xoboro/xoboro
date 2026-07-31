# Releasing

How a Xoboro version is decided, cut, and published.

## Versions come from tags, not from a file

`build.gradle.kts` carries `version = "0.1.0-SNAPSHOT"` and **it is not the release version**. It exists
so that a locally built artifact has a name; it is never edited as part of cutting a release.

The release version is the git tag. This follows from the branch strategy: `main` is the only permanent
branch, and a tag is the deployment snapshot. A version in a tracked file would be a second source of
truth that has to be edited, reviewed, merged, and then agree with the tag — three places to disagree
where one suffices.

## Deciding the number

The number follows from the commit types since the previous tag, per the commit convention:

| Highest-impact commit since the last tag | Next version |
| --- | --- |
| any `!` / `BREAKING CHANGE` | MAJOR (`1.4.2` → `2.0.0`) |
| any `feat` | MINOR (`1.4.2` → `1.5.0`) |
| only `fix` | PATCH (`1.4.2` → `1.4.3`) |
| only `chore` | no release |

The highest impact wins, and it wins **once**. Fifty `fix` commits are one PATCH bump, not fifty.

To read the range:

```shell
git log --oneline "$(git describe --tags --abbrev=0)..HEAD"
```

`!` is visible in that one-line output, which is the reason the convention requires it in the subject
rather than only in the body: deciding a version must not require opening every commit.

## Cutting a release

1. **Confirm `main` is green.** `./gradlew check` locally, and the CI workflows if they are enabled —
   see the note below.
2. **Read the commit range** and decide the number as above.
3. **Tag an annotated tag** on the `main` commit being released:
   ```shell
   git tag -a 1.5.0 -m "1.5.0"
   git push origin 1.5.0
   ```
   Annotated, not lightweight: a release tag carries an author and a date, and `git describe` prefers it.
4. **Publish the container image** for the tag. `.github/workflows/container.yml` builds it.
5. **Write the release notes** from the commit range. The convention exists so this is mechanical:
   `feat` subjects are the features, `fix` subjects are the fixes, `chore` is omitted, and anything with
   `!` goes first with its body quoted, because that body is where the migration instruction lives.

## Maintaining an older version

A `release/x.y.z` branch is created **only when a site needs a fix on a version older than `main`** — not
routinely at release time. It branches from the release tag, and it is never merged back into `main`.
Fixes flow to it by cherry-pick, so `main` stays the single line of development.

Delete the branch when no deployment uses that version any more.

## Database migrations and downgrades

A release may add Flyway migrations. Migrations are forward-only and required to be backward compatible
at the schema level — an added column with a default, a new table — so that a **running** older server
does not break if it briefly sees a newer schema during a rolling restart.

There is no downgrade path. Flyway does not undo migrations, and a rollback to an older release must be
paired with restoring a database backup taken before the upgrade. `docs/architecture` records the
storage-resilience and backup design; the operational instruction is simply: **back up before
upgrading**, and treat a downgrade as a restore rather than as a version change.

## What is not automated, and why it is listed here

- **No automatic tagging.** Nothing infers a version from commits and pushes a tag. Deciding to release
  is a human decision about whether a moment is a good one to ship, which no commit message expresses.
- **No changelog file.** The commit range is the changelog, and a tracked `CHANGELOG.md` would be a
  second thing to keep in agreement with it. Release notes are generated per release from the range.
- **CI is currently paused.** `.github/workflows/{ci,container,differential}.yml` have their triggers
  commented out and run only on `workflow_dispatch`, because GitHub Actions is unavailable on this
  account (#128). Until that is resolved, "confirm `main` is green" means a local `./gradlew check` **and
  a local `npm test && npm run build` in `web/`**, and the container image has to be built by hand or by a
  manual workflow run. This is a real gap in the release process, recorded rather than glossed over.

  The web job exists in `ci.yml` and is paused by the same missing trigger block as the backend one, so
  the UI's tests and build do not run anywhere automatic today either. Two things make the local run
  non-optional: `npm test` once reported "224 passed" while exiting `1` on unhandled errors, so the exit
  code is what has to be checked rather than the summary line; and the production build catches what the
  tests structurally cannot, because a bad import resolves under Vitest and fails only when the bundle is
  linked.
