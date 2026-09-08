# Xoboro web UI design

This is the design contract for Xoboro's two web surfaces: the **reader** and the
**administrator console**. It fixes the decisions that are expensive to change
later — where the code lives, what the design tokens are, how a screen talks to
the server, and what a destructive action is allowed to look like — so that
individual screens can be built without relitigating them.

It is a design document, not a plan. It states what the UI *is*; the build order
lives in the coverage table.

## Starting point

`simple-komga` (a separate repository at `../simple-komga`) is a working
Svelte 5 mobile-first reader for Komga. It is the base for the reader, and its
stack is adopted wholesale:

| Concern | Choice | Why |
|---|---|---|
| Framework | Svelte 5 (runes: `$state`, `$derived`, `$effect`, `$props`) | Already written and working; no runtime VDOM, so the reader's per-page image work stays cheap |
| Build | Vite | Already configured, including a dev proxy |
| Routing | `svelte-spa-router` (hash) | Already working; hash routing needs no server rewrite rule, which matters because the server currently serves no static assets at all |
| i18n | `svelte-i18n` (`ko`, `en`) | Already has a populated catalog |
| Icons | `lucide-svelte` | Already used consistently |
| Tests | Vitest + `@testing-library/svelte` + jsdom | 12 suites already exist |

No new dependencies are introduced by this design. Anything that would need one
is called out under [Deliberately not designed](#deliberately-not-designed).

### What is ported, and what is rewritten

The reader's **hard parts are ported as-is**, because they encode real
solutions:

- the single-flight priority image loader (`priorityLoad` / `schedulePump`),
  which loads the current page first and never lets a prefetch starve it;
- reserving each slot's `aspect-ratio` from the page manifest *before* the image
  loads, without which collapsed zero-height images defeat lazy loading;
- landscape-vs-portrait spread splitting with direction-aware half ordering;
- `IntersectionObserver`-driven progress with a debounced flush and an
  `onDestroy` flush.

The **data layer is rewritten, not ported.** `simple-komga/src/lib/api.js`
targets Komga's `/api/v1` and `/api/v2`. ADR 0082 freezes the compatibility
surface as transitional, so every screen here targets `/api/xoboro/v1`. The
response shapes, the error envelope, the auth transport and the event stream are
all different. Treating this as a port would smuggle a frozen contract into the
new client.

## Where the code lives

```
web/
  index.html
  package.json
  vite.config.js
  vitest.config.js
  src/
    main.js
    App.svelte            # session gate + shell selection only
    lib/                  # http transport, errors, session, sse, i18n — no components
    lib/messages/         # one catalog per locale, identical key sets
    styles/tokens.css     # the single source of design tokens
    styles/global.css
    reader/               # reader shell, routes, components
    admin/                # admin shell, routes, components
    components/           # used by BOTH shells; nothing else goes here
  tests/
```

One Svelte file contains one component, matching the repository's standing rule.

`web/` is a standalone npm project, not a Gradle module. Wiring npm into the
Gradle build would need a plugin, which is a new dependency, and the server does
not currently serve static assets anyway. The build boundary is therefore:
`npm run build` produces `web/dist`, and serving it is a **separate, currently
unimplemented server change** (see [Serving](#serving)).

## Two audiences, one application

The reader and the administrator want opposite things. A reader wants an
immersive, thumb-driven, mostly-image surface on a phone. An administrator wants
dense tables, exact numbers, and keyboard-driven editing on a desktop.

**Decision: one application, one session, two shells, lazily loaded.**

- One build, one origin, one session — an administrator is also a reader, and
  making them log in twice to two deployments would be self-inflicted.
- Shared foundation: tokens, API client, session, i18n, SSE, and the handful of
  genuinely generic components.
- Separate shells: `reader/` and `admin/` share no layout, no navigation and no
  density assumptions, because their users and devices do not.
- `admin/` is a **dynamically imported route chunk**. A reader who never opens
  the console never downloads it. This is what makes "one application" cost
  nothing at the reader's expense.

Rejected: two separate Vite projects. It duplicates the session layer, the
token layer and the i18n catalog — three things that must not drift — to avoid a
problem that a dynamic import already solves.

## Design tokens

`simple-komga` defines four variables (`--bg`, `--fg`, `--muted`, `--accent`)
and then hard-codes roughly a dozen more values inline across components:
`#14141a`, `#17171c`, `#202027`, `#26262f`, `#292930`, `#2a2a33`, `#34343d`,
`#c8c8ce`, `#d7d7dc`, `#ff5a5a`, `rgba(20,20,25,.98)`. Those are real values
doing real work, but as literals they cannot be changed, themed, or checked for
contrast.

**Decision: promote every one of them into a named semantic token in
`styles/tokens.css`, and forbid raw colour literals in component styles.**

Semantic, not descriptive — `--surface-raised`, not `--grey-800`. A screen
should ask for the role it needs, so that changing the palette is one file.

```css
:root {
  /* surface */
  --surface:          #0e0e10;  /* page background */
  --surface-raised:   #14141a;  /* inputs, cards, chips at rest */
  --surface-overlay:  #14141aFA; /* sheets, panels, fixed bars */
  --surface-selected: #202027;

  /* line */
  --line:             #26262f;  /* default border */
  --line-strong:      #34343d;  /* border on an interactive control */

  /* text */
  --text:             #ececf1;
  --text-muted:       #8a8a99;  /* secondary; NOT for anything essential */
  --text-strong:      #ffffff;

  /* intent */
  --accent:           #00d564;  /* brand + primary action */
  --accent-contrast:  #04140a;  /* text ON accent */
  --danger:           #ff5a5a;
  --danger-contrast:  #1a0505;
  --warning:          #ffb84d;
  --success:          #00d564;

  /* geometry */
  --radius-sm: 8px; --radius: 12px; --radius-lg: 22px; --radius-pill: 999px;

  /* elevation */
  --shadow-panel: 0 12px 40px rgba(0,0,0,.5);
  --shadow-sheet: 0 -18px 50px rgba(0,0,0,.45);
}
```

### Theme

Dark only, in both shells, in the first release.

A light theme is the obvious ask for an administrator working in daylight, and
the token layer above is structured so one can be added by overriding
`:root[data-theme="light"]` and nothing else. It is deliberately **not** shipped
half-built: a theme that is wired but untested on every screen is worse than an
honest single theme, because it invites screenshots nobody has checked.

### Spacing and type

Two scales, used everywhere, because ad-hoc values are what produce
almost-aligned screens.

```
space: 4 8 12 16 24 32 48
font:  11 13 15 16 19 24   (px)
```

`16px` is the floor for any text input. iOS Safari zooms the viewport on focus
below that, and the resulting layout jump is not recoverable in CSS.

### Density

The one place the shells legitimately diverge.

| | Reader | Admin |
|---|---|---|
| Base row height | 44px minimum touch target | 36px table row |
| Base font | 15–16px | 13–15px |
| Layout | single column, `env(safe-area-inset-*)` respected everywhere | 12-column grid from 1024px, sidebar + content |
| Primary input | touch | keyboard and pointer |

Note that 36px table rows are **below** the 44px touch minimum. That is
deliberate and bounded: admin tables target pointer and keyboard. Every admin
*action* — button, menu item, checkbox — still meets 44px, so the console
remains usable on a tablet even though its tables are dense.

## Reader

Ported screens: **Login, Home, Series, Reader, Collections, Collection,
ReadLists, ReadList.** The information architecture is already right and is not
being redesigned.

### Home shelves come from the server now

`simple-komga` calls four separate Komga endpoints and independently decides
what "latest" sorts by. Xoboro has named discovery feeds whose ordering is fixed
server-side (ADR 0103), precisely so clients cannot disagree about that.

**Decision: shelves are driven by the five named feeds** — `new`, `updated`,
`recently-read`, `on-deck`, `keep-reading`. The client does not pass `sort` to a
feed; the server rejects it rather than ignoring it, so a client that tries has
a bug, not a preference.

### Scan progress is an event, not a poll

`Home.svelte` currently polls with a hand-tuned backoff
(`[5000, 10000, 20000, 30000, 60000, 60000, 60000]`) to notice that a scan has
finished, because Komga gave it nothing better. Xoboro has a native event
stream.

**Decision: subscribe to the event stream; delete the backoff ladder.** This is
not only tidier — the measured runtime queues over ten thousand tasks on a
15,000-item catalog, so "poll until the numbers stop changing" is a poor
question to keep asking, and the poll interval has no relationship to when the
work actually lands.

### Three readers, not one

The current reader handles paged images only. Xoboro indexes three media kinds,
and the reader must branch on the item's kind:

| Kind | Source format | Reader |
|---|---|---|
| `COMIC` | CBZ/ZIP, CBR/RAR | Ported image reader (scroll / paged / split / split-scroll) |
| `NOVEL` | EPUB (incl. fixed-layout, DiViNa) | **New.** Resource-based, reflowable; progress is a Readium locator |
| `BOOK` | PDF | **New.** Server-rendered pages, so it reuses the image reader's loader and chrome |

The PDF reader is cheap: the server renders pages, so it is the image reader
with a different page source. The EPUB reader is genuinely new work — reflowable
text, its own typography controls (font size, line height, margin, theme), and
locator-based rather than page-number-based progress.

### Reader requests belong to the route

`ReaderRoute` makes one bounded `/media-items/{id}/reader-context` request. The
response contains the current item, nullable adjacent IDs, and only the page or
position manifest that reader needs; it never embeds page bytes or an unbounded
series. Routed children consume that response without repeating item, manifest,
previous, or next reads. A directly mounted reader keeps the same one-request
fallback for reuse and component tests.

The route owns the abort controller. Replacing an item aborts its context request,
clears the active image source and releases the single-flight slot before the next
item starts. This request lifetime is browser-local; durable scans and other server
jobs are not cancelled by navigation.

### Navigation and completion are explicit

Previous, next, back, and list transitions replace the reader history entry. They
do not add one entry per chapter, so Back returns to the catalogue rather than
walking every item opened in the reader.

Displaying the last page number is not completion. Comic and EPUB progress send an
explicit `completed` value: the first half of a final split spread, a final scrolling
page above its bottom, and an EPUB final resource above its bottom remain incomplete.
Only the final logical view completes the item. Older native/compatibility callers
that omit the field retain the historical page-derived behavior.

### Progress conflict is a non-blocking status

`simple-komga` writes progress with `.catch(() => {})`. Xoboro answers
`409 stale_progress` when a newer position already exists, specifically so a
second device cannot silently rewind the reader's place (ADR 0101). Swallowing
that error would throw away the entire point of the contract.

**Decision: reconcile from the winning progress embedded in the `409`.** The
reader advances its client clock from that response without a follow-up item GET,
keeps the current view in place, and reports the write failure in a fixed live
status region rather than interrupting reading with a popup. A later successful
write clears that status.

### Search, paging, and catalogue restoration have one owner

Quick search issues typed and event-refresh requests only while advanced search is
closed. Opening advanced search aborts quick work and transfers the current query;
advanced search is then the sole owner and cancels superseded result and facet
requests. Criteria changes normalize the page to zero before the next request.

Series detail sends its selected zero-based page and keeps the shared pager and
ordering control available through the final page. Home stores only catalogue page
and scroll offset, scoped by session user and library; it restores the page before
loading and the scroll offset only after that page renders. Search text and results
are not persisted.

### Browser acceptance remains a release gate

Automated tests cover request ownership and DOM behavior, but they do not replace a
real mobile browser. Release acceptance still verifies history replacement, series
and Home restoration, safe areas, no horizontal page scrolling, minimum touch
targets, keyboard access, reduced motion, contrast, input focus without viewport
zoom, image retry, and EPUB publisher styles/reflow on the production build.

### Reader accessibility

The current reader has real gaps, and they are being fixed in the port rather
than carried over:

1. **No keyboard path to the chrome.** `.scroll` and `.stage` are `div`s with
   `onclick`. Arrow keys page, but nothing opens the top bar. Fix: a real
   control for chrome, and `Escape` closes it.
2. **The settings panel is not a dialog.** It is a plain fixed `div` — no
   `role`, no `aria-modal`, no `Escape`, no focus handling. `FilterSheet.svelte`
   in the same codebase does all of this correctly. Fix: the panel adopts
   `FilterSheet`'s pattern; the pattern itself moves into a shared component so
   the two cannot diverge again.
3. **No focus trap or focus restore, even in `FilterSheet`.** It sets
   `role="dialog"`, `aria-modal="true"`, locks body scroll and handles `Escape`
   — but never moves focus into the sheet and never restores it on close, so a
   keyboard user is left where they were, behind a modal. Fix in the shared
   component: focus the sheet on open, trap `Tab` within it, restore focus to
   the invoking control on close.
4. **Page images carry `alt="p12"`.** That is noise for a screen reader — a
   position, not a description. Fix: page images are decorative
   (`alt=""`, `role="presentation"`), and the page position is exposed once as
   live text in the chrome, where it is actually useful.

## Administrator console

The console is new. Its screens map to route groups that already exist in the
native API, which is what keeps this section from being wishful:

| Screen | Backed by |
|---|---|
| Overview | `GET /metrics`, `GET /tasks`, library availability |
| Libraries | `libraries` + library administration (create, replace, delete, scan, analyze, metadata-refresh, empty-trash, availability re-check) |
| Trash | `series` / `media-items` with `trashed=true` |
| Tasks | `GET /tasks`, `DELETE /tasks/unclaimed`, `DELETE /tasks/dead` |
| Users | user administration, roles, library grants, content restrictions |
| API keys | key self-service with role-subset scopes and absolute expiry |
| Security | external login configuration (read-only), authentication activity |
| Metadata | metadata editing, field locks, bulk updates, facets |
| Duplicates | duplicate pages: candidates, carriers, decisions |
| Collections & read lists | collection/read-list administration and ordering |
| Settings | server settings (typed, with restart-required reporting), client settings |
| History | historical activity paging |
| Backups | create, list, delete |

Eleven of these are built. **Metadata and Collections & read lists are not**, and
they are deliberately the two that are least like the rest: both are editing
surfaces over content the caller can already see, and metadata editing is
explicitly *not* administrator-gated on the server. They belong with the catalog
screens rather than with the operational console, and are tracked separately.

The console navigation lists only the screens that exist. An entry for an unbuilt
screen is a promise the console does not keep, and it costs an operator a click to
find that out.

### Layout

Sidebar navigation, single content column, no nested scroll regions. Tables get
their own horizontal scroll container; the page body never scrolls sideways.

### The table is the console

One `DataTable` pattern, for every list screen whose endpoint is **paged**:
server-side pagination, never client-side over a full fetch. Every table states
its total from the server's page metadata. "Showing 50" with no denominator is
the thing an administrator cannot act on.

Sorting, a selection column and a per-row overflow menu were specified here and
are **not built**. Sorting was speculative — none of these screens has a listing
long enough for column sort to beat the library filter, and the native sort
parameters differ per resource; a selection column needs a bulk action to serve,
and no console action is bulk; an overflow menu hides destructive actions behind
a click, which works against the rule below that they be visible and separated.
They are recorded as not built rather than left in as a description of something
that does not exist.

Two things the component requires from a caller, both learned the hard way when a
screen first carried two listings. A row's identity comes from `keyOf`, because
`id` is not universal — duplicate pages are identified by `pageHash` — and keying
by position makes a survivor inherit a removed row's element. And each listing is
given a `name`, not derived from its caption, which is translated: it is what makes
the paging test hooks unambiguous when two tables share a screen.

Screens whose endpoint answers a bare array — libraries, users, API keys,
backups — have no envelope to page and use a plain table. The dividing line is
the endpoint's shape, not the screen's importance, and any screen whose endpoint
is paged belongs on `DataTable`: duplicate pages was written with its own table
against a paged endpoint, and rendered nothing at all until it was moved over.

### Destructive actions

This is the console's central safety requirement, not a detail. The actions
below either destroy catalog rows or touch files on disk:

- delete a library (and, with `force=true`, delete one whose storage is
  currently unreachable);
- empty a library's trash;
- discard unclaimed or dead tasks;
- delete a backup;
- delete a user;
- record a duplicate-page deletion decision.

**Rules, applied uniformly:**

1. **Never the default affordance.** A destructive action is never the primary
   button in a row or a form. It lives in an overflow menu or a clearly
   separated danger region.
2. **State the blast radius in numbers, from the server.** "Empty trash" is
   meaningless; "destroy 412 trashed entries in *Synthetic Library*" is
   actionable. The count is fetched before the dialog is shown, not estimated.
3. **Typed confirmation for anything unrecoverable.** Deleting a library or
   emptying trash requires typing the resource's name. A checkbox is not
   friction, it is a reflex.
4. **`force=true` is a second, separate decision.** The server refuses to delete
   an unavailable library with `409 library_unavailable` specifically so a
   missing mount does not cost a catalog. The UI surfaces the refusal, offers
   **Re-check availability** first, and only then offers a distinctly-labelled
   force override that says what it overrides. `force` is never a pre-ticked
   checkbox in the first dialog.
5. **Never offer what the server will not do.** Duplicate-page *removal* is
   deliberately unimplemented: `DELETE_AUTO` and `DELETE_MANUAL` are recorded as
   stated intent and `deleteCount` is always 0 (ADR 0100). The UI presents these
   as *recording a decision*, and says removal is not yet performed. A button
   labelled "Delete pages" that deletes nothing would be a lie in the interface.
6. **Restore is absent by design.** Backup restore requires taking the live
   database offline and is CLI-only. The backups screen says so and names the
   `xoboro restore <path>` command rather than hiding a capability the operator
   needs to know exists.

### Settings

Server settings report whether a change needs a restart by `databaseSource`
differing from `effectiveValue` — the API deliberately reports the fact rather
than a derived flag that could disagree with it.

**Decision: render exactly that fact.** A field whose stored value differs from
its running value shows both, inline: "Saved: 8 · Running: 4 — restart
required". No global "restart needed" banner computed on the client, because a
client-side derivation is the disagreement the API design avoided.

## Data layer

### Session transport

`COOKIE`. The web app is same-origin, and the server sets `XOBORO-SESSION` as
`HttpOnly`, `SameSite=Strict`, plus `Secure` over verified HTTPS.

This is a security improvement over the base, not a preference.
`simple-komga/src/lib/auth.js` stores `btoa(user + ':' + pass)` in
`localStorage` — **the reusable password, recoverable by any script on the
origin, for the lifetime of the browser profile.** An `HttpOnly` cookie is not
readable by script at all, and the server stores only a digest of the session
token, so a database copy is not a session.

Consequences the client must honour:

- The token is never in JS. `hasCredentials()` cannot exist. Session state comes
  from `GET /session`, and "logged out" is a `401` from any call.
- Mutations need browser provenance: an exact same-origin `Origin` header or
  `Sec-Fetch-Site: same-origin`. Same-origin `fetch` sends these; the client
  must not defeat it by pointing at an absolute cross-origin URL.
- `BEARER` exists for non-browser clients and is not used by this UI.

### One transport, no ad-hoc `fetch`

`lib/http.js` owns every request. Screens never call `fetch`, and neither does
any endpoint module: they take a path and go through `request()`. That is what
keeps the base path, the credentials mode, the error envelope and the
lost-session signal single-sourced.

Endpoint groups live beside it as thin modules (`lib/session.js`, and one per
route group as its screens arrive) rather than in one growing file. The rule is
about there being a single **transport**, not a single file — a 2,000-line module
listing every endpoint would satisfy the letter of "one file" and none of its
point.

Two properties are enforced by test rather than by convention, because both fail
silently:

- **Paths are validated as arguments, not as results.** Prefixing the API root
  always yields a same-origin string, so checking the concatenated URL is a guard
  that can never fire. What the check actually catches is a full or
  protocol-relative URL arriving in server data and being mangled into a path
  that fetches the wrong thing.
- **Array parameters repeat their key.** The native listings take repeated
  `libraryId`, `genre` and `tag` filters; joining an array into one
  comma-separated value would silently filter by an identifier that does not
  exist, and the screen would look like it worked.

### Errors branch on `code`

The native envelope is `{ "code", "message" }`, and clients must branch on
`code` — `message` is human-readable text, not a contract.

**The `message` field is never rendered directly to a user.** Every code the UI
handles maps to a translated string, because `message` is English server prose
and this UI ships in Korean and English.

Verified codes that need distinct UI treatment:

| `code` | UI |
|---|---|
| `authentication_required` (401) | Drop to the login gate; preserve intended route |
| `rate_limit_exceeded` (429) | Show the `Retry-After` wait; disable submit until it passes |
| `stale_progress` (409) | The conflict flow described above |
| `library_unavailable` (409) | Offer re-check, then a separate force override |
| `library_root_missing` / `library_root_not_directory` / `library_name_conflict` / `library_root_overlap` (400) | Inline field error on the responsible field, not a toast |
| `server_already_claimed` (409) | Setup is done; go to login |
| `*_forbidden` (403) | Not an error toast — the affordance should not have rendered. Log it and re-read the session |

> **Correction to `docs/api/native-v1.md`.** That document's "Known caveat"
> block states that a global `StatusPages` handler flattens every native 403/404
> body to `{"code":"forbidden"}`/`{"code":"not_found"}`, discarding
> route-specific codes. **That is stale.** `Application.kt:360` now guards the
> handler with the `XoboroNativeErrorBodyWritten` attribute, so a route that
> already wrote its specific code is left alone. Specific 403/404 codes do
> survive in production wiring. The caveat is removed as part of this work —
> left standing, it would have caused this UI's error handling to be designed
> around a defect that no longer exists.

### Live updates over SSE, one connection

One event-stream subscription per session, in `lib/sse.js`, fanned out to
subscribers. Not one connection per screen — the server documents connection
limits and reverse-proxy requirements, and a console with eight live panels must
not open eight streams.

Reconnect uses `Last-Event-ID` for resume. A stream that has been down long
enough to lose its position triggers a refetch of what is on screen rather than
silently showing stale rows.

Closing an EventSource during navigation is normal completion. The native route
recognizes direct `ClosedWriteChannelException` and the engine's
`ChannelWriteException` variant at its send boundary, closes the subscription via
`use`, and returns. Production `StatusPages` recognizes the same two narrow types
before its generic handler, so it neither logs `Unhandled request failure` nor
attempts a synthetic 500 after the response is committed. Authentication,
capacity, replay, revocation, reconnect, cancellation, serialization, and event-hub
failures otherwise retain their existing behavior.

### Performance budgets

Grounded in measured numbers from `docs/performance.md`, not guesses. On a
15,050-item catalog with the task queue drained, `api.series_listing` p50 is
3.8 ms while the media-item listing is about 25 ms — roughly 7× more expensive.

- Prefer series-shaped queries for browse surfaces; reach for media-item
  listings when the screen is actually about items.
- Always page. No screen fetches an unbounded list.
  `simple-komga` asks for `size=500` and `unpaged=true` in several places; both
  are carried over as defects to fix, not as patterns.
- The reader's single-flight image loader is a hard requirement, not an
  optimisation: without it a long chapter's prefetches starve the page the
  reader is looking at.

## i18n

`ko` and `en`, both complete at all times. A missing key is a build-visible
failure, not a silently rendered key path.

Every user-visible string comes from the catalog. No literal copy in a
component, including error text, empty states, and `aria-label`s.

**Tests assert on keys, roles and accessible names — never on Korean or English
copy.** Copy changes then do not break tests, and the repository's standing rule
that test sources contain no Hangul continues to hold in the web project.

## Accessibility standard

Applied to both shells:

- Keyboard-reachable for every action. Where a pointer gesture is the natural
  interaction (tap-to-toggle chrome, swipe to page), a keyboard equivalent
  exists alongside it.
- Modals: `role="dialog"`, `aria-modal="true"`, focus moved in on open, `Tab`
  trapped, focus restored on close, `Escape` closes. One shared component owns
  this so no screen reimplements it.
- Visible focus on every interactive element. Never `outline: none` without a
  replacement.
- Nothing essential communicated by colour alone. A library's unavailable state
  is a label and an icon, not a red dot.
- Text contrast meets WCAG AA. `--text-muted` (`#8a8a99` on `#0e0e10`) is for
  secondary text only and never carries information the user needs.
- Live regions for asynchronous outcomes: scan started, save failed, session
  expired.

## Serving

The server had no `staticFiles`, no `staticResources` and no
`singlePageApplication` route, so there was no way to deploy the UI at all. That
is what the serving change adds.

### The base path is real, and it is an application concern

An earlier draft of this document claimed the server had no application-level
base path and that the base-path support recorded as READY was reverse-proxy
level. **That was wrong.** `Application.kt` wraps its entire routing tree in
`route(contextPath, routes)`, from `runtime.effectiveServerContextPath` — a
server setting. A deployment under `/xoboro` therefore answers the native API at
`/xoboro/api/xoboro/v1`.

This is not a documentation nicety: a client with a hardcoded `/api/xoboro/v1`
would **404 against every endpoint** on such a deployment, and would do it only
there, so nobody would see it until a user with a context path did.

So the API root is derived at runtime, and derived from **this bundle's own
URL** rather than from `location.pathname`. The distinction matters because the
server falls back to `index.html` for an unknown path: `/xoboro/a/b` can
legitimately be the current location, and reading the root off the path would
give `/xoboro/a/`. The script's location does not move when the user navigates.
`location.pathname` is only the fallback, for the dev server where the entry is
`/src/main.js` and there is no asset directory to cut.

Two consequences are pinned by test, because both fail silently and only on a
context-path deployment:

- The derivation keeps only the **path** of the asset URL, never its origin. An
  absolute base would defeat the `SameSite=Strict` cookie and the same-origin
  provenance the mutations require, in one step.
- Vite's `assetsDir` is pinned to `assets`, because the derivation cuts the
  bundle URL at `/assets/`. Renaming it would break every request under a context
  path and nowhere else.

`base: './'` makes the built asset references relative for the same reason.

### Not shadowing the API

- Hash routing means a legitimate deep link is `<root>/#/series/1`, whose path is
  just the served directory. The unknown-path fallback exists for robustness, not
  as the routing mechanism — which is the usual way an SPA rewrite rule quietly
  swallows an API route.
- Static serving is registered so that `/api/**` and `/opds/**` reach their
  routes, with a test for each rather than an assumption about matcher precedence.
- The served directory is configuration, not a build product baked into the jar.
  Wiring npm into Gradle would add a plugin dependency and make the server build
  need Node; a directory the Docker image copies `web/dist` into needs neither.

`XOBORO_WEB_PATH` names the directory, defaulting to `web` beside the working
directory. The container image builds the UI in its own Node stage and copies the
output to `/opt/xoboro/web` — deliberately **not** under `/config`, which is a
mounted volume where a previous release's UI would survive an upgrade.

A missing or half-copied directory is not an error. The check is for `index.html`
specifically, and a directory without one counts as not deployed: registering the
route for an empty directory would make the wildcard answer `404` for paths that
should have reached an API route. A headless deployment is legitimate — refusing
to start without a UI would make the API unusable for anyone who only wants the
API.

The shell is served `no-cache` and hashed assets `max-age` far out. Without that
split, a browser holding the previous deployment's `index.html` requests asset
names that no longer exist, and the result is a blank page a reload does not fix.

## Deliberately not designed

Named, with the reason, rather than left as an implied gap:

- **WebP artwork output.** Decided, not blocked: WebP is **read** (TwelveMonkeys
  supplies the `ImageIO` reader, so `.webp` covers decode) and **not written**.
  Output stays JPEG because `PageImageFormat` offers JPEG and PNG only and the
  artwork processor writes JPEG unconditionally, so nothing could emit WebP even
  if a writer were present. See ADR 0104. The UI requests server-chosen artwork
  formats and does not offer a format toggle.
- **Duplicate-page removal.** Deliberately unimplemented server-side. The UI
  records decisions and says so.
- **Backup restore.** CLI-only by design. The UI names the command.
- **OAuth2/OIDC provider registration.** Provider configuration is
  environment-only so client secrets never enter the database, and the API
  declines writes rather than deferring them. The security screen is read-only
  for providers, and says why.
- **Offline reading.** The current PWA ships a self-destroying service worker on
  purpose. Offline holdings are a future mobile-client capability, not part of
  this UI.
- **Light theme.** Token layer is ready; the theme is not shipped half-built.

## Decisions taken since

Kept rather than deleted, because the reasoning is what a later reader needs.

1. **How `web/dist` is served — settled: from the server.** `XoboroWebAssetRoutes`
   serves a directory named by `XOBORO_WEB_PATH`, not the jar's resources, so the
   Gradle build gains no npm dependency; the container image copies `web/dist`
   into it. A single artifact is what makes the Compose deployment work with no
   extra moving part. The route is registered last and guards a reserved-prefix
   list, because one wildcard is all it takes for an unmatched API path to answer
   with the shell. Base paths follow the `contextPath` server setting, which wraps
   the whole routing tree — not proxy configuration.
2. **Light theme in the first release — settled: no.** Dark ships, the token seam
   stays. Listed under "deliberately not designed" above.
3. **`lucide-svelte` → `@lucide/svelte` — settled: switched.** Same project under
   its current name; the old package's own maintainer points at the new one.
