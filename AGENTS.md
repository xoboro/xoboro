# Xoboro agent instructions

## Mission

Build Xoboro as a production replacement for the user-facing capabilities of
Komga 1.25.0.

Work in this order:

1. Finish the useful UI-free backend workflows tracked by the release gate.
2. Port the simple-komga reader UI and build a new administrator UI.
3. Profile and eliminate Komga's backend bottlenecks.

There is no percentage, line-count, or endpoint-parity target. Feature coverage
is a prioritization inventory for user-visible workflows, not a requirement to
copy every Komga capability. Do not require wire compatibility, database
compatibility, or reproduction of Komga defects. Preserve standards-based
client protocols where interoperability requires it. Provide explicit migration
tools for historical Komga data.

Do not begin UI implementation while required backend rows in
`docs/feature-coverage.md` remain incomplete.

## Required engineering behavior

- Add or update automated tests with every production behavior.
- Run `./gradlew check` before committing backend changes.
- Treat `docs/feature-coverage.md` as the auditable release gate.
- Keep Komga attribution and MIT notices in `README.md`, `NOTICE`, and
  `third-party/licenses/KOMGA.txt`.
- Use synthetic generated fixtures only. Never commit personal library names,
  credentials, paths, media, covers, or scraped metadata.
- Prefer explicit module boundaries and reusable application services over
  framework-specific boilerplate.
- Design Xoboro-native APIs around Xoboro use cases. Do not add Komga-shaped
  behavior unless a standards-based client or migration requirement needs it.
- Do not preserve known security weaknesses, race conditions, performance
  bottlenecks, or accidental behavior for compatibility.
- Long-running media work belongs in durable background jobs, never directly
  in HTTP request handlers.
- Keep server/core code in this repository. Native mobile code belongs in the
  separate private repository.

## Design phase

When UI work is authorized by the backend gate:

- Use the `ui-ux-pro-max` skill before making visual or interaction decisions.
- Generate and persist `design-system/MASTER.md`.
- Add page-specific overrides under `design-system/pages/`.
- Maintain `docs/design.md` with tokens, responsive rules, accessibility
  requirements, interaction rules, and explicit anti-patterns.
- Verify no horizontal mobile scrolling, predictable back navigation,
  state/scroll restoration, safe areas, minimum touch targets, keyboard
  access, reduced motion, and light/dark contrast.
