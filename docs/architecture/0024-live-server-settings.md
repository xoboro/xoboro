# ADR 0024: Durable server settings with live-safe effects

- Status: accepted
- Date: 2026-07-27

## Context

Komga exposes administrator settings through `GET` and `PATCH
/api/v1/settings`. Some values affect live services, while server port and
context path become effective only during the next process start. Merely
persisting and echoing these values would produce a wire-compatible facade with
different runtime behavior.

## Decision

- Store settings as typed values behind the portable `ServerSettingStore`
  boundary while retaining Komga's durable key names.
- Preserve the distinction between omitted update fields and explicit JSON
  `null` for nullable settings.
- Emit explicit JSON nulls in the settings response, independent of the
  application-wide null-omission policy used by other Komga DTOs.
- Apply task-pool size changes immediately through a dynamically resizable
  worker pool. Worker identities remain unique for the process lifetime.
- Resolve persisted task-pool size before workers start.
- Read remember-me signing key and validity through live providers. Rotating
  the key immediately invalidates existing signed tokens; duration changes
  affect newly issued tokens and cookies without restarting.
- Resolve persisted server port and context path before the HTTP engine starts.
  Database values override the ordinary configured/default values, matching
  Komga's web-server customizer behavior. The settings response continues to
  report configuration, database, and effective sources separately.
- Mount every application route, including health and compatibility routes,
  beneath the effective context path.
- Treat Kobo proxy and converter values as durable settings now. Their runtime
  effects will be connected when the corresponding protocol implementations
  land.

## Consequences

Live-safe settings no longer require process replacement, while listener and
base-path changes have deterministic restart semantics. The API remains partial
until differential tests cover exact upstream validation and error envelopes,
and Kobo settings become operational with the Kobo compatibility block.
