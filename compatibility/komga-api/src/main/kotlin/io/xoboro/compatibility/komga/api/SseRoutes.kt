package io.xoboro.compatibility.komga.api

import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.xoboro.core.domain.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.komgaSseRoutes(
  events: KomgaSseEventHub,
  tasks: KomgaTaskStatusProvider = KomgaTaskStatusProvider { KomgaTaskQueueSseDto() },
  heartbeatPeriod: Duration = 15.seconds,
  taskPeriod: Duration = 10.seconds,
) {
  require(heartbeatPeriod.isPositive()) { "SSE heartbeat period must be positive" }
  require(taskPeriod.isPositive()) { "SSE task period must be positive" }
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
        try {
          while (true) {
            val event =
              withTimeoutOrNull(taskPeriod) {
                subscription.receive()
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

  fun publish(
    name: String,
    dataJson: String,
    adminOnly: Boolean = false,
    userIdOnly: String? = null,
  ) {
    require(name.isNotBlank()) { "SSE event name must not be blank" }
    require(dataJson.isNotBlank()) { "SSE event data must not be blank" }
    val event = KomgaSseEvent(name, dataJson)
    subscriptions.values
      .asSequence()
      .filter { !adminOnly || it.user.isAdmin }
      .filter { userIdOnly == null || it.user.id.value == userIdOnly }
      .forEach { state -> state.channel.trySend(event) }
  }

  inline fun <reified T> publishJson(
    name: String,
    data: T,
    adminOnly: Boolean = false,
    userIdOnly: String? = null,
  ) {
    publish(name, SSE_JSON.encodeToString(data), adminOnly, userIdOnly)
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
