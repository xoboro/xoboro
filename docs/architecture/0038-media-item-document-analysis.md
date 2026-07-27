# ADR 0038: Media-item document analysis

- Status: accepted
- Date: 2026-07-27

## Context

Xoboro must replace Komga's PDF and EPUB readers without turning those formats
into separate catalog roots. It must also leave room for local native clients
and future Video and Audio analysis while keeping source downloads bounded and
restart-safe.

## Decision

- Analyze PDF and EPUB through the same source materialization boundary used by
  Comic archives. Whole-file hashing, task scheduling, remote-source cleanup,
  and persistence are shared.
- Represent PDF as the `Book` specialization and EPUB as the `Novel`
  specialization of `MediaItem`. Keep the existing Komga Book vocabulary only
  in repository and REST adapters.
- Persist EPUB resource roles, navigation trees, fixed-layout/DiViNa/KEPUB
  traits, and Readium positions as normalized media-analysis artifacts.
- Parse EPUB container and package paths with archive-root containment. Stream
  only resources present in the analyzed manifest and disable scripts and
  objects through response CSP.
- Index PDF pages without rendering them during scans. Render or extract one
  requested page after materialization so scan cost remains proportional to
  document structure rather than decoded pixels.
- Generate WebPub EPUB/PDF manifests and Readium position lists in the Komga
  compatibility adapter from portable analysis artifacts.

## Consequences

Large scans avoid eager PDF rasterization and preserve EPUB structure across
restarts. Remote sources are materialized once per analysis or read operation
and always closed. Future native APIs can expose the same Novel and Book
artifacts without inheriting Komga DTOs, while timeline analysis for Video and
Audio can reuse the task/source/persistence pattern.
