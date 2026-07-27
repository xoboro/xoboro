# syntax=docker/dockerfile:1.7

ARG TEMURIN_VERSION=26.0.1_8

FROM --platform=$BUILDPLATFORM eclipse-temurin:${TEMURIN_VERSION}-jdk-jammy AS build

WORKDIR /workspace

COPY . .

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :server:app:installDist

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

ENV XOBORO_PORT=25600 \
    XOBORO_DATABASE_PATH=/config/xoboro.sqlite \
    XOBORO_FONTS_PATH=/config/fonts

USER 10001:10001

EXPOSE 25600
VOLUME ["/config"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl --fail --silent --show-error \
    "http://127.0.0.1:${XOBORO_PORT}${XOBORO_CONTEXT_PATH:-}/ready" \
    >/dev/null || exit 1

ENTRYPOINT ["/opt/xoboro/bin/app"]
