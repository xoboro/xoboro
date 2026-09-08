# Reader Performance Design

## Context

Xoboro currently has two distinct latency problems that compound in the browser.
First, every request reaching the macmini container through OrbStack waits roughly
eight seconds before Ktor starts responding. The trusted-proxy interceptor reads
`remoteHost` on every request, even when no forwarded header exists. OrbStack presents
the gateway as `192.168.107.1`; reverse DNS for that address times out after about eight
seconds. Inside the container the same readiness request completes in about one
millisecond, which localizes the delay to request admission rather than catalog work.

Second, requests that do reach the application perform too much repeated work. Browser
session authentication writes `last_accessed_at_ms` for every asset and page request.
Native artwork responses load the full catalog object and blob before evaluating cache
validators. Native page delivery loads the item and media twice, then hashes the entire
body to construct an ETag. The reader route fetches the same item again after its parent
already fetched it. Series and home screens use several general-purpose endpoints and
wait for avoidable request waterfalls.

Simple-komga provides the interaction baseline: render the shell immediately, request
only the data needed by the current screen, and avoid refetching data already owned by
a parent. Xoboro keeps its current palette, native workflows, security model, SSE
updates, and correctness improvements rather than copying simple-komga wholesale.

## Goals

- Remove the eight-second reverse-DNS delay from ordinary container requests.
- Keep trusted-forwarded-header enforcement without trusting an unverified proxy.
- Avoid a SQLite session write for every authenticated asset or page request.
- Evaluate native artwork and page validators before opening or buffering media.
- Stream native media without constructing a second complete in-memory copy.
- Make series and reader entry use purpose-built screen data instead of repeated general
  catalog hydration.
- Remove duplicate reader item requests and progressively reveal independent home data.
- Bound catalog-event refreshes to one in-flight refresh plus at most one dirty follow-up.
- Treat a disconnected SSE client as normal lifecycle completion rather than a server
  error.
- Split non-current reader routes from the initial bundle while preserving the existing
  visual system and responsive behavior.
- Measure the resulting experience on the deployed macmini against the same paths in
  Komga and simple-komga.

## Non-goals

- Replacing Xoboro with the simple-komga frontend or adopting Komga's internal API.
- Cancelling durable scans when a browser changes route or library.
- Weakening HttpOnly sessions, trusted-proxy checks, authorization, or cache privacy.
- Rebuilding search, changing its match semantics, or replacing SQLite.
- Adding a dependency, service, cache daemon, or speculative client state framework.
- Returning every reader or home datum from one unbounded response.
- Changing the color palette or redesigning screens in this performance pass.

## Chosen approach

Fix the server hot path first, then remove client request duplication. Frontend-only
work cannot compensate for the measured eight-second origin delay, while a broad port
would discard already-correct Xoboro behavior. The implementation therefore proceeds
from the smallest shared bottleneck outward:

1. request admission and session authentication;
2. artwork and page delivery;
3. series/reader use-case APIs;
4. frontend request ownership and event coalescing;
5. initial bundle splitting and deployed measurement.

Each behavior change starts with a focused failing test. Existing public API behavior
is retained unless this document explicitly defines a new Xoboro-native endpoint or
validator contract.

## Request admission

The trusted-proxy interceptor determines whether any supported forwarded header is
present before reading the physical peer address. Requests without forwarded headers
never resolve or normalize a peer hostname. Requests with forwarded headers compare the
raw socket address (`remoteAddress`) against the normalized configured trusted hosts.
This keeps spoofed-forwarding protection while avoiding reverse DNS completely.

Tests inject a peer-address reader into the interceptor. They prove it is not called for
an ordinary request, is called for a forwarded request, accepts a configured address,
and rejects an untrusted address before forwarded headers are installed.

## Session write amortization

The session row remains the authority for revocation and expiry. Authentication always
loads the row and user, but it only extends an active session when the persisted last
access is older than a bounded touch interval. A one-minute interval is short relative
to the seven-day inactivity window and converts a page-image burst from one write per
request to at most one normal refresh per session per minute.

An already expired row still follows the repository's atomic `touchIfActive` path. This
preserves the current concurrency behavior where another request may have extended the
session after the stale read. Failed touches still fail authentication. Tests cover the
fresh-session no-write path, interval boundary, expiry, revocation, and concurrent
authoritative refresh behavior.

## Native media delivery

### Artwork

Artwork authorization uses a small access projection containing only owner/library
visibility and current artwork metadata. The route evaluates `If-None-Match` and
`If-Modified-Since` from that metadata before loading the blob. A matching validator
returns `304` with no content read. A cache miss opens the content once and streams it
to the response.

The validator derives from stable artwork metadata such as identity/version, byte size,
and update time; it does not require hashing the body on every request. Private cache
headers and authorization remain unchanged.

### Pages

The native page path uses a delivery projection with the book identifier, library
visibility, media status, page count, source version, and update time. It checks bounds,
authorization, and validators before opening the archive/PDF/EPUB page. A weak native
ETag is derived from the media identity/version and page index. It intentionally need
not equal Komga's body-derived ETag because native Xoboro clients have no wire-
compatibility requirement. Standards-facing Komga compatibility routes retain their
existing validator behavior.

The response opens the requested page once and copies it directly to Ktor's output
stream. It does not call `readBytes`, retain a second whole-body byte array, or hydrate
the complete table-of-contents/page model just to authorize delivery. Tests verify that
`304`, unauthorized, and out-of-range responses never open content, while `200` opens
and streams exactly once.

## Screen-oriented native APIs

The reader gains a bounded context endpoint for initial navigation. It returns the
selected item and only the adjacent identifiers/metadata needed for previous/next
navigation. It does not include page bytes, artwork blobs, or an unbounded series.
Series detail gains the bounded resume/ordering information currently assembled through
sequential general-purpose requests. Both are application use cases backed by explicit
read projections rather than Komga-shaped compatibility endpoints.

The existing endpoints remain available during the change. The web client switches to
the native contexts, which permits independent server and client rollback and avoids a
breaking migration.

## Frontend request lifecycle

`ReaderRoute` owns the initial reader-context request and passes the loaded item into the
comic or EPUB reader. Child readers do not refetch it. Navigation changes abort the
previous context and page-manifest requests; durable server jobs remain unaffected.

Series mutations patch the returned item/state when the mutation response is sufficient
instead of refetching the whole screen. A full refresh remains the fallback only when a
mutation invalidates ordering or membership.

Home starts independent shelf and series requests together but assigns each successful
result as it arrives, so one slow feed does not hide the others. Existing generation and
abort ownership continues to prevent a previous library from overwriting the selected
one.

Catalog event refresh uses a tiny single-flight coordinator. While a refresh is running,
events merge into one strongest pending scope. Completion triggers at most one follow-up
refresh; further events merge into that same pending scope. This bounds work during a
scan without suppressing the final state. Component disposal aborts browser requests and
clears the coordinator, but never cancels durable scans.

SSE writes that fail because the client closed the channel are handled as normal
completion and do not reach generic request-failure logging as a `500`.

## Bundle and shell behavior

The authenticated application shell and current route render independently of lazy
route modules. Home, search, series, comic reader, EPUB reader, and administrator routes
are dynamically imported at route boundaries, with a small route fallback that uses the
existing palette. The session gate stays in place because protected data must not render
before authentication, but the surrounding shell may render while session status is
unknown when it does not expose private data.

No new router or state dependency is introduced. The current Svelte/Vite mechanisms are
sufficient, and one source file continues to contain one primary component.

## Failure and compatibility behavior

- An untrusted forwarded request is still rejected.
- Session revocation and expiry remain immediately observable on the next request.
- Media authorization occurs before validators are honored, preventing cache probes from
  revealing private resource existence.
- A metadata-derived native ETag changes whenever the stored media/artwork version used
  to open content changes. If the source cannot supply a trustworthy version, the route
  omits the shortcut instead of returning a possibly stale `304`.
- Failed screen endpoints retain existing error presentation and retry behavior.
- Compatibility APIs and their standards-required response shapes remain unchanged.

## Verification

Automated regression coverage proves:

- ordinary requests never resolve a proxy hostname;
- forwarded requests retain the trusted-peer boundary;
- a burst of authenticated image requests performs at most one session touch per minute;
- matching media validators perform zero blob/archive opens;
- successful native delivery streams one content source exactly once;
- reader entry makes one item/context request and children reuse it;
- independent home results render without waiting for the slowest request;
- event bursts during an in-flight refresh cause only one merged follow-up;
- closing SSE does not produce a server-error response or unhandled exception;
- initial production output contains separate route chunks.

The repository gates are `npm test`, `npm run build`, and `./gradlew check`. Container
acceptance then measures, from the macmini host and inside the container, readiness,
root/static assets, session, series, artwork, reader context, manifest, and first-page
time. The same warmed host path is sampled against Komga and simple-komga. The critical
acceptance conditions are:

- no request exhibits the former eight-second DNS plateau;
- host readiness time is within normal local proxy overhead of the in-container result;
- cached artwork/page requests return before body access;
- reader entry has no duplicate item fetch;
- scan-time event traffic remains bounded;
- Xoboro's first useful reader content is faster than Komga on the deployed catalog, or
  any remaining measured blocker is reported rather than hidden.

## Delivery

The changes are committed on `fix/reader-performance`, pushed, and merged through a pull
request into `main`. CI and GHCR workflows remain enabled. Deployment uses the image
built from merged `main`, recreates the macmini service, verifies readiness and image
identity, and runs browser-level search, library-switch, series, and first-page smoke
checks. No local development image is substituted for the published artifact.
