package io.xoboro.compatibility.komga.api

import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [users] is deliberately required rather than defaulted. It is the stream's only revocation
 * control, and a default would let a call site silently opt out of it — the same failure mode
 * as omitting a route-scoped security plugin.
 */
fun Route.komgaSseRoutes(
  events: KomgaSseEventHub,
  users: KomgaSseUserSnapshot,
  tasks: KomgaTaskStatusProvider = KomgaTaskStatusProvider { KomgaTaskQueueSseDto() },
  heartbeatPeriod: Duration = 15.seconds,
  taskPeriod: Duration = 10.seconds,
  authorizationRecheckPeriod: Duration = taskPeriod,
) {
  require(heartbeatPeriod.isPositive()) { "SSE heartbeat period must be positive" }
  require(taskPeriod.isPositive()) { "SSE task period must be positive" }
  require(authorizationRecheckPeriod.isPositive()) {
    "SSE authorization recheck period must be positive"
  }
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    sse("/sse/v1/events") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      heartbeat {
        period = heartbeatPeriod
        event = ServerSentEvent(comments = "heartbeat")
      }
      events.subscribe(principal.user).use { subscription ->
        if (principal.user.isAdmin) {
          send(tasks.snapshot().toServerSentEvent())
        }
        var nextAuthorizationCheck = TimeSource.Monotonic.markNow() + authorizationRecheckPeriod
        try {
          while (true) {
            val event =
              withTimeoutOrNull(taskPeriod) {
                subscription.receive()
              }
            // Checked on every wake, not only on the task-poll timeout: a stream busy with
            // catalog events never times out, so checking only the timeout branch would keep
            // a revoked subscriber live for as long as events keep arriving. The deadline
            // bounds the cost to one lookup per recheck period per subscriber regardless of
            // event volume.
            if (nextAuthorizationCheck.hasPassedNow()) {
              val current = users.currentOrNull(principal.user.id)
              if (current == null || current.invalidatesKomgaSessionFrom(principal.user)) {
                close()
                return@use
              }
              nextAuthorizationCheck =
                TimeSource.Monotonic.markNow() + authorizationRecheckPeriod
            }
            if (event == null) {
              if (principal.user.isAdmin) {
                send(tasks.snapshot().toServerSentEvent())
              }
            } else {
              send(event.toServerSentEvent())
            }
          }
        } catch (_: SseHubClosedException) {
          close()
        }
      }
    }
  }
}

class KomgaSseEventHub(
  private val subscriberCapacity: Int = DEFAULT_SUBSCRIBER_CAPACITY,
) : AutoCloseable {
  private val nextId = AtomicLong()
  private val subscriptions = ConcurrentHashMap<Long, SubscriptionState>()

  init {
    require(subscriberCapacity > 0) { "SSE subscriber capacity must be positive" }
  }

  fun subscribe(user: User): KomgaSseSubscription {
    val id = nextId.incrementAndGet()
    val channel =
      Channel<KomgaSseEvent>(
        capacity = subscriberCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )
    subscriptions[id] = SubscriptionState(user, channel)
    return KomgaSseSubscription(channel) {
      subscriptions.remove(id)?.channel?.close()
    }
  }

  /**
   * [libraryId] scopes an event to subscribers who can access that library. It is an
   * in-memory set membership test against the subscriber's grants, so it costs no query and
   * works identically for removals — the identifier travels inside the domain event rather
   * than being looked up, so a deleted row does not make the event undeliverable.
   *
   * Passing null broadcasts to every subscriber. That is correct only for events whose
   * payload carries no library scope; see [KomgaSseEventBridge] for which those are and ADR
   * 0087 for why they remain unscoped.
   */
  fun publish(
    name: String,
    dataJson: String,
    adminOnly: Boolean = false,
    userIdOnly: String? = null,
    libraryId: LibraryId? = null,
  ) {
    require(name.isNotBlank()) { "SSE event name must not be blank" }
    require(dataJson.isNotBlank()) { "SSE event data must not be blank" }
    val event = KomgaSseEvent(name, dataJson)
    subscriptions.values
      .asSequence()
      .filter { !adminOnly || it.user.isAdmin }
      .filter { userIdOnly == null || it.user.id.value == userIdOnly }
      .filter { libraryId == null || it.user.canAccessLibrary(libraryId) }
      .forEach { state -> state.channel.trySend(event) }
  }

  inline fun <reified T> publishJson(
    name: String,
    data: T,
    adminOnly: Boolean = false,
    userIdOnly: String? = null,
    libraryId: LibraryId? = null,
  ) {
    publish(name, SSE_JSON.encodeToString(data), adminOnly, userIdOnly, libraryId)
  }

  override fun close() {
    subscriptions.keys.toList().forEach { id ->
      subscriptions.remove(id)?.channel?.close()
    }
  }

  private data class SubscriptionState(
    val user: User,
    val channel: Channel<KomgaSseEvent>,
  )

  companion object {
    const val DEFAULT_SUBSCRIBER_CAPACITY: Int = 128
  }
}

class KomgaSseSubscription internal constructor(
  private val channel: Channel<KomgaSseEvent>,
  private val onClose: () -> Unit,
) : AutoCloseable {
  suspend fun receive(): KomgaSseEvent =
    channel.receiveCatching().getOrNull() ?: throw SseHubClosedException()

  override fun close() = onClose()
}

data class KomgaSseEvent(
  val name: String,
  val dataJson: String,
)

fun interface KomgaSseUserSnapshot {
  /** Returns the subscriber's current row, or null once the account no longer exists. */
  fun currentOrNull(id: UserId): User?
}

/**
 * Mirrors the exact field set `UserLifecycle.updateUser` compares before expiring sessions, so
 * an open stream closes precisely when the subscriber's session would have been invalidated.
 * Keeping one field set means there is no second, drifting definition of "authorization
 * changed".
 *
 * Deliberately not a whole-[User] comparison: the adaptive password rehash that runs on an
 * ordinary successful login rewrites `passwordHash` and `updatedAtMillis`, and closing streams
 * on that would disconnect users for simply logging in elsewhere.
 */
internal fun User.invalidatesKomgaSessionFrom(connected: User): Boolean =
  email != connected.email ||
    roles != connected.roles ||
    sharedLibraryIds != connected.sharedLibraryIds ||
    sharesAllLibraries != connected.sharesAllLibraries ||
    restrictions != connected.restrictions

fun interface KomgaTaskStatusProvider {
  fun snapshot(): KomgaTaskQueueSseDto
}

@Serializable
data class KomgaTaskQueueSseDto(
  val count: Int = 0,
  val countByType: Map<String, Int> = emptyMap(),
)

@Serializable
data class KomgaLibrarySseDto(
  val libraryId: String,
)

@Serializable
data class KomgaSeriesSseDto(
  val seriesId: String,
  val libraryId: String,
)

@Serializable
data class KomgaBookSseDto(
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
)

@Serializable
data class KomgaBookImportSseDto(
  val bookId: String? = null,
  val sourceFile: String,
  val success: Boolean,
  val message: String? = null,
)

@Serializable
data class KomgaCollectionSseDto(
  val collectionId: String,
  val seriesIds: List<String>,
)

@Serializable
data class KomgaReadListSseDto(
  val readListId: String,
  val bookIds: List<String>,
)

@Serializable
data class KomgaReadProgressSseDto(
  val bookId: String,
  val userId: String,
)

@Serializable
data class KomgaReadProgressSeriesSseDto(
  val seriesId: String,
  val userId: String,
)

@Serializable
data class KomgaSessionExpiredSseDto(
  val userId: String,
)

@Serializable
data class KomgaThumbnailBookSseDto(
  val bookId: String,
  val seriesId: String,
  val selected: Boolean,
)

@Serializable
data class KomgaThumbnailSeriesSseDto(
  val seriesId: String,
  val selected: Boolean,
)

@Serializable
data class KomgaThumbnailCollectionSseDto(
  val collectionId: String,
  val selected: Boolean,
)

@Serializable
data class KomgaThumbnailReadListSseDto(
  val readListId: String,
  val selected: Boolean,
)

private fun KomgaSseEvent.toServerSentEvent(): ServerSentEvent =
  ServerSentEvent(
    data = dataJson,
    event = name,
  )

private fun KomgaTaskQueueSseDto.toServerSentEvent(): ServerSentEvent =
  ServerSentEvent(
    data = SSE_JSON.encodeToString(this),
    event = "TaskQueueStatus",
  )

private class SseHubClosedException : IllegalStateException("SSE event hub is closed")

@PublishedApi
internal val SSE_JSON: Json = Json { explicitNulls = false }
