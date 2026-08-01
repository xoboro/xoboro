#!/usr/bin/env bash
#
# Checks that the packaged container image is a deployable release, not just a process that starts.
#
# The container workflow's smoke test waits for the healthcheck to report `healthy`, and the
# healthcheck asks `/ready`. That answers a question about the server and none about the release: the
# web UI is a separate build stage copied in at `Dockerfile:53`, so a wrong path there, a renamed
# `dist/`, or a `XOBORO_WEB_PATH` that no longer matches would ship an image with no user interface
# and still report healthy. Nothing caught that class of defect, because nothing asked the image for
# a page.
#
# This asks. It fetches the document, extracts every asset the document itself references - rather
# than a hardcoded list of filenames, which would go stale the moment the bundler changes a hash -
# and requires each one to be served. It then repeats the whole check under a context path, which is
# the entire reason the built document references its assets relatively; a base-path deployment that
# 404s on every asset is indistinguishable from a working one until someone opens a browser.
#
# It runs the container the way `compose.yaml` deploys it: read-only root filesystem, all Linux
# capabilities dropped, and `no-new-privileges`. The workflow's smoke test dropped no capabilities,
# so it passed under weaker confinement than any real deployment runs with - a test that cannot fail
# the way production fails.
#
# Usage:
#   scripts/container-acceptance.sh [image]
#
# With no argument the image is built from the working tree. Pass an existing tag to check an image
# that is already built:
#   scripts/container-acceptance.sh xoboro:smoke
set -euo pipefail

IMAGE="${1:-}"
CONTEXT_PATH="/xoboro"
PLAIN_PORT="${XOBORO_ACCEPTANCE_PORT:-25900}"
CONTEXT_PORT="${XOBORO_ACCEPTANCE_CONTEXT_PORT:-25901}"
PLAIN_NAME="xoboro-acceptance-plain"
CONTEXT_NAME="xoboro-acceptance-context"

repository_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
  pwd
}

cd "$(repository_root)"

cleanup() {
  docker rm --force "$PLAIN_NAME" "$CONTEXT_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ -z "$IMAGE" ]]; then
  IMAGE="xoboro:acceptance"
  echo "building $IMAGE from the working tree"
  docker build --tag "$IMAGE" .
fi

# Exactly the confinement `compose.yaml` deploys under. `/config` and `/tmp` are tmpfs because the
# root filesystem is read-only and the server must still be able to write its database and cache;
# `/config` is owned by the image's unprivileged user, which is what makes running as that user work.
start_container() {
  local name="$1" port="$2"
  shift 2
  docker run --detach --name "$name" \
    --read-only \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --tmpfs /tmp:rw,exec,size=1g,mode=1777 \
    --tmpfs /config:rw,size=256m,mode=0755,uid=10001,gid=10001 \
    --publish "127.0.0.1:$port:25600" \
    "$@" \
    "$IMAGE" >/dev/null
}

await_healthy() {
  local name="$1" status
  for _ in $(seq 1 90); do
    status="$(docker inspect --format '{{.State.Health.Status}}' "$name")"
    case "$status" in
      healthy) return 0 ;;
      unhealthy)
        docker logs "$name"
        echo "container $name reported unhealthy" >&2
        return 1
        ;;
    esac
    sleep 2
  done
  docker logs "$name"
  echo "container $name never became healthy" >&2
  return 1
}

echo "starting $PLAIN_NAME at the root path"
start_container "$PLAIN_NAME" "$PLAIN_PORT"
await_healthy "$PLAIN_NAME"

echo "starting $CONTEXT_NAME under $CONTEXT_PATH"
start_container "$CONTEXT_NAME" "$CONTEXT_PORT" --env "XOBORO_CONTEXT_PATH=$CONTEXT_PATH"
await_healthy "$CONTEXT_NAME"

# The unprivileged user is asserted rather than assumed: the Dockerfile sets `USER 10001:10001`, and
# an image that silently reverted to root would still pass every other check here.
echo "checking the container runs unprivileged"
identity="$(docker exec "$PLAIN_NAME" id -u)"
if [[ "$identity" != "10001" ]]; then
  echo "expected uid 10001, got $identity" >&2
  exit 1
fi

python3 - "$PLAIN_PORT" "$CONTEXT_PORT" "$CONTEXT_PATH" <<'PY'
"""Requires the image to serve a usable web UI at both deployments.

Standard library only: this runs inside the container workflow, where adding a Python dependency
would mean a pip install in CI for a check this small.
"""

import re
import sys
import urllib.error
import urllib.request

plain_port, context_port, context_path = sys.argv[1], sys.argv[2], sys.argv[3]
failures = []


def fetch(port, path):
    url = f"http://127.0.0.1:{port}{path}"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.status, response.headers.get("Content-Type", ""), response.read()
    except urllib.error.HTTPError as failure:
        return failure.code, "", b""


def check_ui(port, prefix, label):
    """The document, then every asset the document itself names.

    Reading the references out of the document is the point: a hardcoded asset list would agree with
    itself and go stale the moment the bundler changes a content hash, which is the same defect this
    script exists to catch, one level up.
    """
    status, content_type, body = fetch(port, f"{prefix}/")
    if status != 200 or "text/html" not in content_type:
        failures.append(f"{label}: GET {prefix}/ answered {status} {content_type!r}, not an HTML document")
        return
    document = body.decode("utf-8", "replace")
    references = re.findall(r'(?:src|href)="([^"]+)"', document)
    if not references:
        failures.append(
            f"{label}: the served document references no assets at all, so it cannot be the built UI"
        )
        return
    print(f"  {label}: document {status}, {len(references)} referenced assets")
    for reference in references:
        if reference.startswith(("http://", "https://", "data:", "#")):
            continue
        resolved = f"{prefix}/{reference.removeprefix('./').lstrip('/')}"
        asset_status, asset_type, asset_body = fetch(port, resolved)
        if asset_status != 200 or not asset_body:
            failures.append(f"{label}: asset {resolved} answered {asset_status}")
            continue
        print(f"    {resolved} -> {asset_status} {asset_type} {len(asset_body)}B")


check_ui(plain_port, "", "root path")
check_ui(context_port, context_path, f"context path {context_path}")

# A context-path deployment must not also answer at the root. If it does, a reverse proxy stripping
# the prefix and one passing it through would both appear to work, and the base-path support would be
# accidental rather than real.
root_status, _, _ = fetch(context_port, "/")
if root_status != 404:
    failures.append(
        f"context path {context_path}: GET / answered {root_status}, expected 404 - "
        "the deployment is answering outside its configured context path"
    )

# The API has to be reachable at the same deployment, and has to refuse an unauthenticated read. A
# UI served next to an API that answers 401 for everything is not a working release either.
setup_status, _, _ = fetch(plain_port, "/api/xoboro/v1/setup")
if setup_status != 200:
    failures.append(f"root path: GET /api/xoboro/v1/setup answered {setup_status}, expected 200")
series_status, _, _ = fetch(plain_port, "/api/xoboro/v1/series")
if series_status != 401:
    failures.append(
        f"root path: unauthenticated GET /api/xoboro/v1/series answered {series_status}, expected 401"
    )

if failures:
    print("\nCONTAINER ACCEPTANCE FAILED", file=sys.stderr)
    for failure in failures:
        print(f"  {failure}", file=sys.stderr)
    raise SystemExit(1)
print("\ncontainer acceptance passed: the image serves the UI and the API at both deployments")
PY
