# Reader Experience Completion Design

## Context

The first reader-performance pass removed the container reverse-DNS plateau and several
duplicate reads, but it did not complete the interaction contract inherited from
`simple-komga`. Reader entry still waits for item metadata before asking for the page
manifest, chapter navigation pushes one browser-history entry per chapter, active image
loads survive route replacement, and a stalled image can block the single-flight queue.

The same audit found independent catalogue and event-stream defects: advanced search has
two request owners, series detail exposes only the first 100 items, home loses paging and
scroll state, and ordinary SSE client disconnects are still logged as server errors in the
deployed container. EPUB delivery works, but it does not preserve progress within a long
spine resource or continue to adjacent media items.

This design completes those workflows without changing Xoboro's palette, authentication,
standards-facing compatibility APIs, or durable scan semantics.

## Goals

- Show the first comic/PDF page after one bounded context request and one image request.
- Make chapter and series navigation replace reader history like `simple-komga`.
- Explicitly cancel obsolete image work and prevent one load from stalling the queue.
- Keep a failed page retryable without taking the reader away from the page.
- Track tall scroll pages by viewport position rather than an impossible visibility ratio.
- Distinguish reaching the final page number from actually completing its final view.
- Preserve EPUB position within a spine resource and continue across adjacent items.
- Ensure one search request owner, cancellable filter choices, and deterministic page reset.
- Make every item in a series reachable and restore home page/scroll state on return.
- Treat ordinary SSE disconnects as normal completion in the production pipeline.
- Remove stale progress-dialog copy and make progress failures non-blocking.
- Bound mobile image width without shrinking tall webtoon pages by their height.

## Non-goals

- Changing the colour palette or general information architecture.
- Cancelling durable server scans when a browser changes screen or library.
- Adding a JavaScript or server dependency.
- Changing Komga, Kobo, KOReader, OPDS, or WebPub compatibility response shapes.
- Adding offline reading or a transformed-image cache daemon.
- Rewriting the router or introducing a global client-state framework.

## Media-item reader context

Add `GET /api/xoboro/v1/media-items/{mediaItemId}/reader-context` to the native delivery
surface. The authenticated caller must have page-streaming permission and catalogue access.
The bounded response contains:

- `item`: the existing native media-item response;
- `previousId` and `nextId`: nullable adjacent media-item identifiers;
- `pages`: the indexed page manifest for comic/PDF items;
- `positions`: the spine-derived position list for EPUB items.

Only the manifest appropriate to the item is populated. No page bytes, resource bytes,
artwork, or unbounded series list is included. A missing or unauthorized item remains a
404. An analyzed item whose media is not ready returns the existing delivery conflict, so
the route-level retry affordance is useful rather than rendering an empty reader.

The catalog repository returns a purpose-built projection containing one hydrated current
item and adjacent identifiers selected with the existing stable sibling order and access
filter. It must not implement the endpoint by hydrating the current, previous, and next
items through three public reads.

`ReaderRoute` owns this request and passes the whole context to the selected child. Comic
and EPUB readers retain a direct-load fallback for component tests and reuse, but routed
entry performs no item, manifest, or adjacent-item follow-up reads.

## Image delivery and loading

The page endpoint gains a native-only `maxWidth` option in addition to the existing
`maxDimension`. The two sizing options are mutually exclusive. `maxWidth` preserves the
full height-to-width ratio, so a narrow, very tall webtoon is not reduced to unreadable
text merely because its height is large. Raw/source delivery cannot be combined with a
resize option. Native ETags include the chosen width.

The browser requests a width derived from the actual display width and device pixel ratio,
capped by the server limit. A split spread requests twice the visible half width. If the
manifest says the source is already no wider than the requested width, it uses the source
URL and avoids a pointless decode/re-encode.

The priority loader owns the active task as well as the queue. Reset, action destruction,
and URL replacement remove event listeners, clear the timeout, and remove `src` before
releasing the slot. A load has a bounded timeout and one automatic retry; final failure
releases the queue and invokes the caller's failure callback. The page slot preserves its
aspect ratio and shows an inline retry control. Retrying remounts only that image.

## Navigation and progress

All transitions from a reader to the previous/next item or back/list destination use the
router's `replace`, never a new history entry. Keyboard, tap, and swipe produce the same
logical forward/backward action in both LTR and RTL.

Comic progress writes include an explicit `completed` boolean. The native request and
application lifecycle accept that optional field; callers that omit it keep the historical
last-page completion behaviour, preserving compatibility routes. The Xoboro reader sends
`false` while merely displaying a page and `true` only when the final logical view has been
reached: the second half of a split final spread, the final paged view, or the bottom of the
final scrolling view. Consequently, the first half of the last spread remains resumable.

Scroll progress uses a thin viewport-centre band rather than `threshold: 0.5`, so an image
taller than two viewports can still become current. Reaching the bottom of the scroll
container marks the final view completed.

Progress writes remain debounced during reading. Final pagehide/visibility flushing uses a
keepalive request; component teardown still performs the ordinary async write. Failures are
reported in a fixed non-layout-shifting status region and a later successful write clears
the status. A stale response carries the stored progress in its 409 body, allowing the
client clock to advance without a follow-up media-item GET and without a modal.

## EPUB behaviour

The EPUB reader consumes the same context and adjacent identifiers. The frame remains
sandboxed without scripts. Because it is same-origin, the parent attaches load, scroll,
click, and keyboard-safe handlers to the frame document. It restores and records the
locator's in-resource `progression`, derives `totalProgression` across the spine, and marks
completion only at the bottom of the final resource. Moving beyond either end replaces the
route with the adjacent media item when present.

An EPUB position count is not its analyzed page count: positions are generated from
uncompressed content spans while page count is a separate publication mapping. The client
therefore never sends raw `position` as the progress page. It resumes by locator href/
position when present and otherwise maps position to a valid page using the same bounded
ratio as the server protocols. Every write keeps `page` inside `1..media.pageCount` while
the locator remains the precise EPUB location.

Reader chrome is page-first like the comic reader: no permanent visual toggle or position
pill. A tap in the frame toggles the existing bars, and the position announcement remains a
visually hidden live region. Parent-injected style variables make font size, line height,
column width, margin, and light/dark publication theme real settings without granting
scripts to EPUB content.

## Search and catalogue state

Quick search runs only while the advanced surface is closed. Opening advanced search aborts
quick search and transfers the debounced query. While advanced is open, it is the sole
request owner for typed queries and catalogue/progress invalidations.

Home passes its already-loaded libraries to `AdvancedSearch`. Filter-choice loading accepts
an abort signal and is cancelled on teardown; only the facet endpoints still need loading.
One reactive request coordinator normalizes a changed query to page zero before issuing a
request, preventing an obsolete nonzero-page request.

Typing a non-empty quick query immediately shows an explicit updating state during the
debounce rather than an unexplained blank or stale result. Series detail uses the shared
Pager and sends the selected zero-based page. Ordering changes reset it to zero.

Home stores selected catalogue page and scroll offset in session storage, scoped by user and
library. It restores the page before loading and restores scroll only after that page has
rendered. Choosing a different library resets both. This is session navigation state, not a
durable preference.

## Event-stream disconnects

The route recognizes `ClosedWriteChannelException` at the SSE send boundary and retains
the engine-variant `ChannelWriteException` handling. The production StatusPages boundary
also recognizes both narrow channel-close types before the generic Throwable handler. It
neither logs them as unhandled nor attempts a second response. The route still closes its
subscription via `use`; authentication, capacity, replay, and revocation behaviour remain
unchanged.

## Testing and acceptance

Every production change starts with a focused failing test. Automated coverage must prove:

- routed reader entry makes exactly one context request before the first image URL appears;
- active image reset clears `src`, timeout/retry cannot stall the next page, and manual retry
  remounts only the failed image;
- previous/next/back use `replace`, including RTL swipe direction;
- a tall slot becomes current and the first half of the final spread stays incomplete;
- stale conflict reconciliation makes no follow-up GET and later writes advance the clock;
- every series page is reachable and home page/scroll state returns after remount;
- advanced search makes one request per query and aborts superseded facet work;
- EPUB restores in-resource progression and crosses adjacent-item boundaries;
- a disconnected SSE response does not reach generic 500 logging;
- `maxWidth` preserves aspect ratio and is part of native validation/cache identity.

Repository gates remain `NODE_OPTIONS=--no-experimental-webstorage npm test`,
`npm run build`, and `./gradlew check`. Docker acceptance uses the production image on the
macmini, verifies image identity and health, then exercises authenticated reader context,
search, series paging, page delivery, progress, and SSE reconnect. Mobile browser acceptance
checks page-first chrome, safe areas, no horizontal scroll, history replacement, resume, and
input focus without viewport zoom.

## Delivery

Work is performed on `fix/reader-experience-completion`, committed with Conventional Commits,
pushed, and opened as a pull request into `main`. CI and GHCR remain enabled. Deployment uses
the image produced from the merged commit when available; until then, no branch image is
reported as the final production image.
