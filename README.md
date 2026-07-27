# Xoboro

Xoboro is a fast, self-hosted media server for comics, manga, webtoons, and
ebooks.

> [!IMPORTANT]
> Xoboro is under active development and is not ready to replace a production
> Komga installation yet.

## Relationship to Komga

Xoboro is derived from and inspired by
[Komga](https://github.com/gotson/komga). The first compatibility baseline is
Komga 1.25.0, and the project aims to reproduce 99.9% of its backend behavior,
API surface, media handling, metadata behavior, and supported protocols before
adding Xoboro-specific capabilities.

Komga is Copyright (c) 2019 Gauthier Roebroeck and is distributed under the
MIT License. Its license is preserved in
[`third-party/licenses/KOMGA.txt`](third-party/licenses/KOMGA.txt), and further
attribution is recorded in [`NOTICE`](NOTICE).

Xoboro is an independent project and is not affiliated with or endorsed by
the Komga project or its maintainers.

## Delivery order

1. Complete the UI-free Xoboro backend with Komga 1.25.0 compatibility,
   migration tooling, protocol support, and automated verification.
2. Port the simple-komga reader experience and build a new administrator UI
   from a documented design system.
3. Profile and remove bottlenecks in scanning, analysis, task scheduling,
   metadata processing, and media delivery.

The future Android and iOS applications will live in a separate private
repository. Cross-platform domain code intended for those clients remains in
the public `core` modules in this repository.

## Repository layout

```text
core/
  application/       Portable use cases and external-service ports
  domain/            Platform-neutral domain model
server/
  app/               Ktor application entry point
  media/             Media analysis and source materialization ports
  persistence/       SQLite migrations and jOOQ persistence adapters
  sources/local/     Local filesystem source adapter
  tasks/             Leased durable worker runtime and task routing
compatibility/       Komga API and protocol adapters
docs/                Architecture, parity, testing, and design records
third-party/         Third-party license notices
```

UI modules are intentionally absent during the backend compatibility phase.

## Build and test

Requirements:

- JDK 17 or newer

Run the complete verification suite:

```shell
./gradlew check
```

Run the server:

```shell
./gradlew :server:app:run
```

The initial health endpoint is available at `http://localhost:25600/health`.

## Compatibility status

The auditable compatibility ledger is maintained in
[`docs/komga-parity.md`](docs/komga-parity.md). A feature is not considered
compatible until behavior, authorization, persistence, error handling, and
automated tests have all been verified.

## Test data policy

No personal library names, credentials, media, covers, or scraped metadata are
committed. Tests generate synthetic archives and metadata in temporary
directories. See [`docs/testing.md`](docs/testing.md).

## License

Xoboro is available under the [MIT License](LICENSE).
