# Xoboro agent instructions

## Mission

Build Xoboro as a production replacement for Komga 1.25.0.

Work in this order:

1. Finish the UI-free backend at 99.9% observable Komga compatibility.
2. Port the simple-komga reader UI and build a new administrator UI.
3. Profile and eliminate Komga's backend bottlenecks.

Do not begin UI implementation while required backend rows in
`docs/komga-parity.md` remain incomplete.

## Required engineering behavior

- Add or update automated tests with every production behavior.
- Run `./gradlew check` before committing backend changes.
- Treat `docs/komga-parity.md` as an auditable release gate.
- Keep Komga attribution and MIT notices in `README.md`, `NOTICE`, and
  `third-party/licenses/KOMGA.txt`.
- Use synthetic generated fixtures only. Never commit personal library names,
  credentials, paths, media, covers, or scraped metadata.
- Prefer explicit module boundaries and reusable application services over
  framework-specific boilerplate.
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

