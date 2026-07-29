package io.xoboro.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.User
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `GET /api/xoboro/v1/events`: the native SSE event stream backed by [XoboroNativeEventHub].
 *
 * ### Why the capacity check and headers live in a route-scoped plugin, not inside the `sse {}` block
 * Ktor's `Route.sse(...)` registers its own `handle {}` at [ApplicationCallPipeline.Call] which
 * sets `Content-Type`, `Cache-Control: no-store` and `X-Accel-Buffering: no` and then calls
 * `call.respond(SSEServerContent(...))` *before* the `ServerSSESession` lambda ever runs — by the
 * time our code inside `sse {}` executes, the 200 response and its headers are already on the
 * wire. That means a decision to answer `503` instead of opening the stream, or a header this
 * route wants to add, cannot be made from inside the `sse {}` block; both have to happen in code
 * that runs before it. [XoboroNativeEventStreamGuardHook] installs a plain
 * [ApplicationCallPipeline.Call]-phase interceptor (route-scoped, ahead of the `sse {}`
 * registration further down, and interceptors on the same node and phase run in registration
 * order) which both makes the capacity/provenance decision and sets
 * `Cache-Control`/`X-Accel-Buffering` ahead of Ktor's own — appended, not replacing
 * (`ResponseHeaders` has no replace API), so the client sees this route's values first and Ktor's
 * own `no-store` merely adds a stricter, harmless extra `Cache-Control` line. `Route.intercept` is
 * deprecated in favour of route-scoped plugins, hence the [Hook] indirection instead of calling it
 * directly.
 *
 * ### Why the provenance check is here at all
 * [XoboroCookieCsrfProtection] only inspects unsafe HTTP methods (POST/PUT/PATCH/DELETE), so a
 * `GET` stream is invisible to it. A cookie-authenticated `EventSource` cannot set an
 * `Authorization` header but *does* send same-origin cookies — `withCredentials: true` from any
 * origin the operator's CORS policy allows would otherwise read a signed-in user's entire live
 * feed cross-site. Bearer transport carries no ambient cookie, so it is exempt, exactly like
 * mutations are.
 *
 * ### Why re-authentication re-resolves through the session, not a raw user lookup
 * [XoboroPrincipal.plainToken] is the session token that authenticated this connection.
 * Re-running it through [UserSessionLifecycle.authenticate] on every wake answers three
 * questions a plain `findByIdOrNull` cannot: whether the session was invalidated (explicit
 * logout, [io.xoboro.core.application.UserLifecycle.updateUser] expiring it), whether it has
 * simply expired, and it extends the session's inactivity timeout so an actively-streaming
 * client is not logged out from under itself. Only the fields [User.invalidatesXoboroStreamFrom]
 * compares can end the stream — the same set `UserLifecycle.updateUser` uses to decide whether to
 * expire sessions at all — so an ordinary password rehash on login elsewhere never disconnects a
 * viewer.
 */
fun Route.xoboroNativeEventRoutes(
  hub: XoboroNativeEventHub,
  sessions: UserSessionLifecycle,
  heartbeatPeriod: Duration = DEFAULT_HEARTBEAT_PERIOD,
  reauthenticationPeriod: Duration = heartbeatPeriod,
  maxStreamLifetime: Duration = DEFAULT_MAX_STREAM_LIFETIME,
) {
  require(heartbeatPeriod.isPositive()) { "Native event heartbeat period must be positive" }
  require(reauthenticationPeriod.isPositive()) {
    "Native event re-authentication period must be positive"
  }
  require(maxStreamLifetime.isPositive()) { "Native event max stream lifetime must be positive" }

  val streamGuard =
    createRouteScopedPlugin("XoboroNativeEventStreamGuard") {
      on(XoboroNativeEventStreamGuardHook) {
        val principal = requireNotNull(call.principal<XoboroPrincipal>())
        if (principal.transport == SessionTransport.COOKIE && !call.hasTrustedMutationOrigin()) {
          call.respondCsrfRejected()
          finish()
          return@on
        }

        val lastEventId = call.request.header(HttpHeaders.LastEventID)
        val subscription = hub.subscribe(principal.user, lastEventId)
        if (subscription == null) {
          call.response.header(HttpHeaders.RetryAfter, CAPACITY_RETRY_AFTER_SECONDS.toString())
          call.respond(
            HttpStatusCode.ServiceUnavailable,
            XoboroApiError(
              code = "event_stream_capacity",
              message = "The server has reached its native event stream capacity",
            ),
          )
          finish()
          return@on
        }

        call.attributes.put(SUBSCRIPTION_KEY, subscription)
        call.response.header(HttpHeaders.CacheControl, "no-cache, no-transform")
        call.response.header(ACCEL_BUFFERING_HEADER_NAME, "no")
      }
    }

  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      route("/events") {
        install(streamGuard)
        sse {
          val principal = requireNotNull(call.principal<XoboroPrincipal>())
          val subscription = call.attributes[SUBSCRIPTION_KEY]
          val deadline = TimeSource.Monotonic.markNow() + maxStreamLifetime
          var nextReauthentication = TimeSource.Monotonic.markNow() + reauthenticationPeriod

          try {
            subscription.use {
              while (!deadline.hasPassedNow()) {
                val envelope = withTimeoutOrNull(heartbeatPeriod) { subscription.receive() }

                // Checked on every wake — event delivery and heartbeat timeout alike — never
                // only on the timeout branch: a stream kept busy by catalog events would
                // otherwise never time out, so a revoked subscriber would stay live for as long
                // as events keep arriving. The deadline bounds the cost to one re-authentication
                // per period regardless of event volume.
                if (nextReauthentication.hasPassedNow()) {
                  val current = sessions.authenticate(principal.plainToken)
                  if (current == null || current.invalidatesXoboroStreamFrom(principal.user)) {
                    send(STREAM_REVOKED_EVENT)
                    return@sse
                  }
                  nextReauthentication = TimeSource.Monotonic.markNow() + reauthenticationPeriod
                }

                if (envelope != null) {
                  send(envelope.toServerSentEvent())
                } else {
                  send(ServerSentEvent(comments = HEARTBEAT_COMMENT))
                }
              }
            }
          } catch (_: XoboroNativeEventHubClosedException) {
            // The hub already delivered its own final resync-required frame (overflow or
            // superseded) through receive() before closing the channel; nothing more to send.
          }
        }
      }
    }
  }
}

/**
 * A [Hook] that exposes a full [PipelineContext] (`call`, `finish()`) at
 * [ApplicationCallPipeline.Call], for route-scoped plugins that need to conditionally short
 * circuit before a route's own handler runs. `Route.intercept` provides the same thing but is
 * deprecated in favour of route-scoped plugins; this hook is the route-scoped-plugin-compatible
 * equivalent, built on the (non-deprecated) [ApplicationCallPipeline.intercept] it wraps.
 */
private object XoboroNativeEventStreamGuardHook : Hook<suspend PipelineContext<Unit, PipelineCall>.() -> Unit> {
  override fun install(
    pipeline: ApplicationCallPipeline,
    handler: suspend PipelineContext<Unit, PipelineCall>.() -> Unit,
  ) {
    pipeline.intercept(ApplicationCallPipeline.Call) { handler() }
  }
}

/**
 * Mirrors the exact field set [io.xoboro.core.application.UserLifecycle.updateUser] compares
 * before expiring sessions, so a stream closes precisely when the subscriber's session would
 * have been invalidated by that same logic — one definition of "authorization changed", not a
 * second one that could drift from it.
 *
 * Deliberately not a whole-[User] comparison: the adaptive password rehash that runs on an
 * ordinary successful login rewrites `passwordHash` and `updatedAtMillis`, and closing the stream
 * on that would disconnect a viewer for simply logging in elsewhere.
 */
private fun User.invalidatesXoboroStreamFrom(connected: User): Boolean =
  email != connected.email ||
    roles != connected.roles ||
    sharedLibraryIds != connected.sharedLibraryIds ||
    sharesAllLibraries != connected.sharesAllLibraries ||
    restrictions != connected.restrictions

private fun XoboroNativeEventEnvelope.toServerSentEvent(): ServerSentEvent =
  ServerSentEvent(
    id = id,
    event = event.name,
    data = event.payload,
  )

@Serializable
private data class XoboroStreamResyncRequiredPayload(
  val reason: String,
  val seq: Long? = null,
)

private val SUBSCRIPTION_KEY: AttributeKey<XoboroNativeEventSubscription> =
  AttributeKey("XoboroNativeEventSubscription")

private val EVENT_STREAM_JSON = Json { explicitNulls = false }

private val STREAM_REVOKED_EVENT =
  ServerSentEvent(
    data = EVENT_STREAM_JSON.encodeToString(XoboroStreamResyncRequiredPayload(reason = "revoked")),
    event = "stream.resync-required",
  )

private const val HEARTBEAT_COMMENT: String = "heartbeat"
private const val ACCEL_BUFFERING_HEADER_NAME: String = "X-Accel-Buffering"
private const val CAPACITY_RETRY_AFTER_SECONDS: Int = 5
private val DEFAULT_HEARTBEAT_PERIOD: Duration = 15.seconds
private val DEFAULT_MAX_STREAM_LIFETIME: Duration = 1.hours
