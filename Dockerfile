# syntax=docker/dockerfile:1.7

ARG TEMURIN_VERSION=26.0.1_8
ARG NODE_VERSION=24-bookworm-slim

FROM --platform=$BUILDPLATFORM eclipse-temurin:${TEMURIN_VERSION}-jdk-jammy AS build

WORKDIR /workspace

COPY . .

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :server:app:installDist

# The web UI is built in its own stage so the server image needs no Node, and so
# the Gradle build needs no npm plugin. Its output is plain JavaScript, so this
# stage runs on the build platform whatever the target architecture is.
FROM --platform=$BUILDPLATFORM node:${NODE_VERSION} AS web

WORKDIR /web

# Manifests first, so a source-only change does not reinstall dependencies.
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY web/ ./
RUN npm run build

FROM eclipse-temurin:${TEMURIN_VERSION}-jre-jammy AS runtime

ARG VERSION=dev
ARG REVISION=unknown
ARG CREATED=unknown

LABEL org.opencontainers.image.title="Xoboro" \
      org.opencontainers.image.description="Self-hosted media server with Komga compatibility" \
      org.opencontainers.image.source="https://github.com/xoboro/xoboro" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}"

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 xoboro \
    && useradd --uid 10001 --gid 10001 --system --home-dir /opt/xoboro xoboro \
    && install --directory --owner=xoboro --group=xoboro /config /media

WORKDIR /opt/xoboro

COPY --from=build --chown=xoboro:xoboro /workspace/server/app/build/install/app/ ./
COPY --from=web --chown=xoboro:xoboro /web/dist/ ./web/

# Not under /config: the UI is a build product shipped with the release, not
# something an operator edits, so it must not live on the mounted volume where an
# old copy would survive an upgrade.
ENV XOBORO_PORT=25600 \
    XOBORO_DATABASE_PATH=/config/xoboro.sqlite \
    XOBORO_FONTS_PATH=/config/fonts \
    XOBORO_WEB_PATH=/opt/xoboro/web

USER 10001:10001

EXPOSE 25600
VOLUME ["/config"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl --fail --silent --show-error \
    "http://127.0.0.1:${XOBORO_PORT}${XOBORO_CONTEXT_PATH:-}/ready" \
    >/dev/null || exit 1

ENTRYPOINT ["/opt/xoboro/bin/app"]
