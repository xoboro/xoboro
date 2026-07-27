# ADR 0004: Source-aware library lifecycle

- Status: accepted
- Date: 2026-07-27

## Context

Komga validates libraries with JVM filesystem paths. Xoboro must retain the same
observable validation and maintenance triggers while later supporting local storage,
WebDAV, and other NAS protocols without embedding protocol code in use cases.

## Decision

- Keep library lifecycle behavior in the portable application module.
- Represent a root as a source identity plus an opaque item identity.
- Delegate existence, directory, and ancestry checks to a `LibraryRootAccess` port.
- Compare ancestry only between roots owned by the same source.
- Delegate scans and maintenance work to a queue port; use cases never perform
  long-running media work inline.
- Publish typed library lifecycle events through a port.

The lifecycle reproduces Komga's maintenance transitions: root and scan-input changes
request a rescan; newly enabled hashes, extension repair, and archive conversion request
their corresponding background operations.

## Consequences

Local and network sources can implement their own canonical path and hierarchy rules.
The lifecycle remains reusable by the server and future native clients. A server
composition layer must provide durable queue, event, and source adapters before exposing
the write API.
