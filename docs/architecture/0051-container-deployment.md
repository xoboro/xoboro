# ADR 0051: Reproducible container deployment

## Status

Accepted.

## Context

Xoboro must run consistently on amd64 servers and arm64 systems such as Apple
Silicon. Operators need a direct Compose entry point, persistent database
storage, safe NAS mounts, health reporting, and automatic images after a main
branch merge.

## Decision

- Build the Gradle application distribution once on the native build platform
  with Eclipse Temurin 26.0.1+8, then copy it into the matching JRE image.
- Run as fixed unprivileged UID/GID 10001 with no Linux capabilities.
- Keep the root filesystem read-only in Compose. Persist `/config`, mount
  `/media` read-only, and provide bounded temporary storage. The `/tmp` tmpfs is
  executable because SQLite JDBC extracts its architecture-specific native
  library there at startup.
- Publish port 25600 by default and make both the host port and optional reverse
  proxy context path configurable.
- Use `/ready` as the context-aware container health check and allow 35 seconds
  for graceful worker and database shutdown.
- Make `docker compose up -d` build a local image when no published image is
  selected.
- Smoke-test the real image on every container-related pull request. After main
  branch merges and version tags, publish SBOM/provenance-enabled amd64 and
  arm64 manifests to GitHub Container Registry.

## Consequences

The same Dockerfile is used locally, in Compose, and by the registry workflow.
Media cannot be modified through the normal container mount; maintenance that
intentionally changes source archives requires an explicitly writable override.
Bind-mounted configuration directories must be writable by UID 10001, while the
default named volume receives the correct image ownership automatically.
