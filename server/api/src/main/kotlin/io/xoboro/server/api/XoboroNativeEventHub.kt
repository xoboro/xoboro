package io.xoboro.server.api

import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The in-process fan-out point for [XoboroNativeEvent]s: every publisher calls [publish], every
 * live SSE connection holds one [XoboroNativeEventSubscription].
 *
 * This is deliberately free of any Ktor type. The hub only produces and filters events; turning
 * an [XoboroNativeEventSubscription] into an actual `sse {}` route, and bridging domain events
 * into [publish] calls, are separate concerns left to later work.
 *
 * ### Sequencing
 * A single [sequence] counter is incremented once per **published** event, before any
 * per-subscriber filtering, so every subscriber shares one numbering. A subscriber who cannot see
 * every event will observe gaps in that numbering — 1837 then 1904 — and that is normal, not
 * loss: the numbering counts publications, not deliveries. Detecting "loss" from those gaps would
 * be detecting a property the sequence was never meant to have.
 *
 * ### Epoch
 * [epoch] is minted once per process and prefixed onto every event id as `"<epoch>:<seq>"`. The
 * ring buffer lives in memory and [sequence] restarts at zero on every process start, so a bare
 * seq number is ambiguous across a restart: a client holding `"1904"` from the previous process
 * could resume against seq 1904 of the new one and get a clean `resumed: true` for a stream that
 * in truth shares nothing with what it remembers. Comparing epochs first turns that silent
 * mismatch into an explicit resync.
 *
 * ### Ring buffer and replay
 * The last [bufferCapacity] **published** events are kept, not per-subscriber copies — a
 * per-subscriber copy would be both larger (one per connection instead of one, shared) and wrong
 * (it would replay whatever the subscriber's grants were when the event was buffered, not what
 * they are now). Replay always re-runs [isVisibleTo] against the reconnecting subscriber's
 * current access. Skipping that on replay would leak harder than the live path does, because a
 * replay arrives as a burst rather than one event at a time.
 *
 * ### Backpressure
 * Each subscriber has a bounded channel of [subscriberCapacity]. Publishing only ever calls
 * [Channel.trySend], which never suspends, so one slow consumer can never stall a publisher — and
 * publishers here run inside library scans, so stalling one would stall the scan. A failed
 * `trySend` means the subscriber's queue is full: the queue is drained (a partial, truncated
 * burst is worse than an explicit gap) and the subscriber is marked overflowed, so the next
 * [XoboroNativeEventSubscription.receive] returns `stream.resync-required {reason: overflow}`
 * instead of a silently incomplete run of events.
 *
 * ### Per-user and per-server limits
 * A user opening a fifth stream does not get rejected — the oldest of their existing streams is
 * closed with `stream.resync-required {reason: superseded}` instead. Rejecting the newest would
 * strand the user behind a connection the server has not yet noticed is half-dead (e.g. a
 * backgrounded tab); closing the oldest guarantees the user always ends up with their newest
 * intent honoured. The server-wide limit works the other way: it simply refuses new subscriptions
 * once [maxTotalStreams] is reached, leaving the reject-with-503 decision to the route.
 */
class XoboroNativeEventHub(
  private val catalog: CatalogReadRepository,
  private val bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
  private val subscriberCapacity: Int = DEFAULT_SUBSCRIBER_CAPACITY,
  private val maxStreamsPerUser: Int = DEFAULT_MAX_STREAMS_PER_USER,
  private val maxTotalStreams: Int = DEFAULT_MAX_TOTAL_STREAMS,
) {
  private val epoch: String = UUID.randomUUID().toString()
  private val sequence = AtomicLong(0)
  private val nextSubscriptionId = AtomicLong(0)

  /**
   * Guards [buffer] and [subscriptionsByUser] together so that "append to the buffer" and
   * "snapshot the subscriber list" (in [publish]) are atomic with respect to "snapshot the
   * buffer" and "register a subscriber" (in [subscribe]). Without one lock covering both sides, a
   * subscriber could register after a publish's snapshot was taken but before that same event
   * reached the ring buffer, and would then see it neither live nor replayed.
   *
   * The lock never guards a suspending call: [Channel.trySend] and [Channel.tryReceive] cannot
   * suspend, so holding it never risks stalling a publisher on a consumer.
   */
  private val lock = ReentrantLock()
  private val buffer = ArrayDeque<BufferedEvent>()
  private val subscriptionsByUser = HashMap<UserId, ArrayDeque<XoboroNativeEventSubscription>>()
  private var totalSubscriptions = 0

  init {
    require(bufferCapacity > 0) { "Native event buffer capacity must be positive" }
    require(subscriberCapacity > 0) { "Native event subscriber capacity must be positive" }
    require(maxStreamsPerUser > 0) { "Native event max streams per user must be positive" }
    require(maxTotalStreams > 0) { "Native event max total streams must be positive" }
  }

  /**
   * Publishes [event] to every live subscriber who may see it, and appends it to the replay
   * buffer regardless of who could see it live — visibility is re-decided at replay time, not
   * baked in at publish time.
   *
   * Never suspends: the only cross-subscriber work under [lock] is a counter increment, a
   * ring-buffer append and copying subscriber references, all bounded and allocation-cheap at the
   * scale this hub runs at (at most [maxTotalStreams] subscribers).
   */
  fun publish(event: XoboroNativeEvent) {
    val (seq, subscribers) =
      lock.withLock {
        val seq = sequence.incrementAndGet()
        buffer.addLast(BufferedEvent(seq, event))
        while (buffer.size > bufferCapacity) buffer.removeFirst()
        seq to subscriptionsByUser.values.flatten()
      }
    val envelope = XoboroNativeEventEnvelope(eventId(seq), event)
    for (subscription in subscribers) {
      if (event.isVisibleTo(subscription.user, catalog)) {
        subscription.enqueue(envelope)
      }
    }
  }

  /**
   * Registers [user] as a subscriber and returns the [XoboroNativeEventSubscription] to read from,
   * or `null` if the server is already at [maxTotalStreams] — callers turn that into a 503, this
   * hub has no opinion on HTTP.
   *
   * [lastEventId] drives the resume decision (see the class doc): it is resolved and the
   * resulting control frames (and any replay) are seeded into the subscription's channel before
   * the subscription becomes reachable from [publish], so a reconnecting client always sees its
   * resume/gap frame before any live event — never interleaved with one.
   */
  fun subscribe(
    user: User,
    lastEventId: String?,
  ): XoboroNativeEventSubscription? =
    lock.withLock {
      if (totalSubscriptions >= maxTotalStreams) return@withLock null

      val currentSeq = sequence.get()
      val initialFrames = resumeFrames(user, lastEventId, currentSeq)

      val id = nextSubscriptionId.incrementAndGet()
      val subscription =
        XoboroNativeEventSubscription(
          id = id,
          user = user,
          channel = Channel(subscriberCapacity),
          resyncOverflow = { resyncEnvelope(user, reason = REASON_OVERFLOW) },
          resyncSuperseded = { resyncEnvelope(user, reason = REASON_SUPERSEDED) },
          onClose = { subscription -> unregister(subscription) },
        )
      initialFrames.forEach { subscription.enqueue(it) }

      val userStreams = subscriptionsByUser.getOrPut(user.id) { ArrayDeque() }
      if (userStreams.size >= maxStreamsPerUser) {
        val oldest = userStreams.removeFirst()
        totalSubscriptions -= 1
        oldest.supersede()
      }
      userStreams.addLast(subscription)
      totalSubscriptions += 1

      subscription
    }

  private fun unregister(subscription: XoboroNativeEventSubscription) {
    lock.withLock {
      val userStreams = subscriptionsByUser[subscription.user.id] ?: return@withLock
      if (userStreams.remove(subscription)) {
        totalSubscriptions -= 1
      }
      if (userStreams.isEmpty()) {
        subscriptionsByUser.remove(subscription.user.id)
      }
    }
  }

  /**
   * Decides what a subscribing connection should be seeded with, per the resume contract:
   * - no [lastEventId] → fresh connection, `stream.ready {resumed: false}`.
   * - unparseable, epoch mismatch, or older than the buffer → `stream.resync-required
   *   {reason: gap}`, no replay.
   * - caught up already (`seq == currentSeq`) → `stream.ready {seq, resumed: true}`.
   * - within the buffer → filtered replay of `(seq, currentSeq]`, then `stream.ready
   *   {seq: currentSeq, resumed: true}`.
   *
   * Called under [lock], so [buffer] and [sequence] are read consistently with whatever
   * [publish] call happens immediately before or after this subscription is registered.
   */
  private fun resumeFrames(
    user: User,
    lastEventId: String?,
    currentSeq: Long,
  ): List<XoboroNativeEventEnvelope> {
    if (lastEventId == null) {
      return listOf(readyEnvelope(user, currentSeq, resumed = false))
    }

    val parsed = parseEventId(lastEventId)
    if (parsed == null || parsed.epoch != epoch) {
      return listOf(gapEnvelope(user, currentSeq))
    }
    if (parsed.seq == currentSeq) {
      return listOf(readyEnvelope(user, currentSeq, resumed = true))
    }

    val oldestBuffered = buffer.firstOrNull()?.seq
    val canReplay = oldestBuffered != null && parsed.seq in (oldestBuffered - 1) until currentSeq
    if (!canReplay) {
      return listOf(gapEnvelope(user, currentSeq))
    }

    val replay =
      buffer
        .asSequence()
        .filter { it.seq > parsed.seq }
        .filter { it.event.isVisibleTo(user, catalog) }
        .map { XoboroNativeEventEnvelope(eventId(it.seq), it.event) }
        .toList()
    return replay + readyEnvelope(user, currentSeq, resumed = true)
  }

  private fun eventId(seq: Long): String = "$epoch:$seq"

  private fun readyEnvelope(
    user: User,
    seq: Long,
    resumed: Boolean,
  ): XoboroNativeEventEnvelope {
    val payload = StreamReadyPayload(seq = if (resumed) seq else null, resumed = resumed)
    return XoboroNativeEventEnvelope(
      eventId(seq),
      XoboroNativeEvent(
        STREAM_READY,
        XoboroNativeEventScope.Owner(user.id),
        CONTROL_EVENT_JSON.encodeToString(payload),
      ),
    )
  }

  private fun gapEnvelope(
    user: User,
    seq: Long,
  ): XoboroNativeEventEnvelope {
    val payload = StreamResyncRequiredPayload(reason = REASON_GAP, seq = seq)
    return XoboroNativeEventEnvelope(
      eventId(seq),
      XoboroNativeEvent(
        STREAM_RESYNC_REQUIRED,
        XoboroNativeEventScope.Owner(user.id),
        CONTROL_EVENT_JSON.encodeToString(payload),
      ),
    )
  }

  /**
   * Builds an overflow or superseded resync frame. Unlike [gapEnvelope], these are not resume
   * decisions made while holding [lock] — they are raised later, from [publish] (overflow) or
   * from an eviction inside [subscribe] (superseded) — so they carry no `seq`: they promise
   * nothing about a resumable position, only that the stream is continuing (overflow) or ending
   * (superseded) from here.
   */
  private fun resyncEnvelope(
    user: User,
    reason: String,
  ): XoboroNativeEventEnvelope {
    val payload = StreamResyncRequiredPayload(reason = reason, seq = null)
    return XoboroNativeEventEnvelope(
      eventId(sequence.get()),
      XoboroNativeEvent(
        STREAM_RESYNC_REQUIRED,
        XoboroNativeEventScope.Owner(user.id),
        CONTROL_EVENT_JSON.encodeToString(payload),
      ),
    )
  }

  private fun parseEventId(raw: String): ParsedEventId? {
    val separator = raw.lastIndexOf(':')
    if (separator <= 0 || separator == raw.length - 1) return null
    val seq = raw.substring(separator + 1).toLongOrNull() ?: return null
    return ParsedEventId(epoch = raw.substring(0, separator), seq = seq)
  }

  private data class BufferedEvent(
    val seq: Long,
    val event: XoboroNativeEvent,
  )

  private data class ParsedEventId(
    val epoch: String,
    val seq: Long,
  )

  companion object {
    const val DEFAULT_BUFFER_CAPACITY: Int = 1024
    const val DEFAULT_SUBSCRIBER_CAPACITY: Int = 256
    const val DEFAULT_MAX_STREAMS_PER_USER: Int = 4
    const val DEFAULT_MAX_TOTAL_STREAMS: Int = 256

    private const val STREAM_READY = "stream.ready"
    private const val STREAM_RESYNC_REQUIRED = "stream.resync-required"
    private const val REASON_GAP = "gap"
    private const val REASON_OVERFLOW = "overflow"
    private const val REASON_SUPERSEDED = "superseded"

    private val CONTROL_EVENT_JSON = Json { explicitNulls = false }
  }
}

/**
 * A live registration with [XoboroNativeEventHub]. Consumers call [receive] in a loop to obtain
 * both domain events (already filtered) and control frames (`stream.ready`,
 * `stream.resync-required`), and call [close] exactly once when the underlying connection ends.
 *
 * Control frames never travel through [channel] under contention with domain events: they are
 * held in [pendingControl] and always drained by [receive] before anything else, so an overflow
 * or supersede signal can never be pushed out by a backlog of ordinary events, and a superseded
 * subscription still reliably delivers its final frame even though its channel is being drained.
 */
class XoboroNativeEventSubscription internal constructor(
  val id: Long,
  internal val user: User,
  private val channel: Channel<XoboroNativeEventEnvelope>,
  private val resyncOverflow: () -> XoboroNativeEventEnvelope,
  private val resyncSuperseded: () -> XoboroNativeEventEnvelope,
  private val onClose: (XoboroNativeEventSubscription) -> Unit,
) : AutoCloseable {
  private val pendingControl = AtomicReference<PendingControl?>(null)

  @Volatile
  private var terminal = false

  internal fun enqueue(envelope: XoboroNativeEventEnvelope) {
    if (terminal) return
    if (channel.trySend(envelope).isFailure) {
      // A truncated burst presented as complete is worse than an explicit gap: drain whatever
      // partial run is queued and let the next receive() announce it instead of delivering it.
      while (channel.tryReceive().isSuccess) {
        // discard
      }
      pendingControl.set(PendingControl.OVERFLOWED)
    }
  }

  /**
   * Closes this subscription from the hub's side: whatever is queued is discarded (the
   * subscriber is being disconnected, not merely gapped) in favour of one final
   * `stream.resync-required {reason: superseded}` frame, and no further event is ever accepted.
   */
  internal fun supersede() {
    terminal = true
    while (channel.tryReceive().isSuccess) {
      // discard
    }
    pendingControl.set(PendingControl.SUPERSEDED)
    channel.close()
  }

  /**
   * Returns the next envelope for this subscription, suspending until one is available.
   *
   * Pending control signals are always returned before anything sitting in [channel], so an
   * overflow or supersede is reported at the point it happened rather than after whatever the
   * channel happened to still hold. Throws [XoboroNativeEventHubClosedException] once the
   * channel is closed (following a supersede) and no control signal remains.
   */
  suspend fun receive(): XoboroNativeEventEnvelope {
    when (pendingControl.getAndSet(null)) {
      PendingControl.OVERFLOWED -> return resyncOverflow()
      PendingControl.SUPERSEDED -> return resyncSuperseded()
      null -> {}
    }
    return channel.receiveCatching().getOrNull() ?: throw XoboroNativeEventHubClosedException()
  }

  override fun close() = onClose(this)

  private enum class PendingControl { OVERFLOWED, SUPERSEDED }
}

/** One event as delivered to a subscriber: its resumable [id] alongside the filtered [event]. */
data class XoboroNativeEventEnvelope(
  val id: String,
  val event: XoboroNativeEvent,
)

/** Thrown from [XoboroNativeEventSubscription.receive] once its stream has ended for good. */
class XoboroNativeEventHubClosedException : IllegalStateException("Native event stream is closed")

@Serializable
private data class StreamReadyPayload(
  val seq: Long? = null,
  val resumed: Boolean,
)

@Serializable
private data class StreamResyncRequiredPayload(
  val reason: String,
  val seq: Long? = null,
)
