# ADR 0001: Backend-first modular monolith

- Status: Accepted
- Date: 2026-07-27

## Context

Xoboro must replace Komga 1.25.0 before native client development begins. The
server includes media analysis, metadata, durable background work, multiple
protocols, security, and a broad compatibility API. Deployments are primarily
single-node self-hosted systems and NAS devices.

## Decision

Xoboro will be implemented as a Kotlin modular monolith using Ktor at the HTTP
boundary.

- Platform-neutral domain logic lives under `core`.
- JVM-specific persistence, filesystem, media, and server code lives under
  `server`.
- Komga compatibility is an adapter over application services, not the domain
  model itself.
- Background work is durable and persisted; request handlers do not perform
  long-running scans or analysis.
- UI development is deferred until backend compatibility is verified.
- Native applications remain in a separate private repository.

## Consequences

- Server and core changes can be committed and tested atomically.
- The deployment remains one process and one container until measured load
  requires worker separation.
- Common code remains suitable for later Kotlin Multiplatform publication.
- Komga compatibility can be tested and removed independently from Xoboro
  internals if it is ever no longer required.

