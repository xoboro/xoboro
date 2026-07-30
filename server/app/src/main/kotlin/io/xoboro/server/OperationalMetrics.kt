package io.xoboro.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

internal class OperationalMetrics(
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private val startedAtNanos = nanoTime()
  private val activeRequests = AtomicLong()
  private val requests = ConcurrentHashMap<RequestMetricKey, RequestMetric>()

  fun beginRequest(): Long {
    activeRequests.incrementAndGet()
    return nanoTime()
  }

  fun endRequest(
    method: String,
    status: Int,
    startedAtNanos: Long,
  ) {
    val elapsed = (nanoTime() - startedAtNanos).coerceAtLeast(0)
    val key = RequestMetricKey(normalizeMethod(method), normalizeStatus(status))
    requests.computeIfAbsent(key) { RequestMetric() }.record(elapsed)
    activeRequests.decrementAndGet()
  }

  fun activeRequestCount(): Long = activeRequests.get()

  fun totalRequestCount(): Long = requests.values.sumOf { it.count.sum() }

  fun requestCountsByStatusClass(): Map<String, Long> =
    requests.entries
      .groupBy({ it.key.status }, { it.value.count.sum() })
      .mapValues { (_, counts) -> counts.sum() }

  fun uptimeSeconds(): Double =
    (nanoTime() - startedAtNanos).coerceAtLeast(0).toDouble() / NANOS_PER_SECOND

  fun scrape(
    ready: Boolean,
    taskQueueSize: Int,
    workerCount: Int,
  ): String {
    require(taskQueueSize >= 0) { "Task queue size must not be negative" }
    require(workerCount >= 0) { "Worker count must not be negative" }
    val uptimeSeconds = uptimeSeconds()
    return buildString {
      appendLine("# HELP xoboro_http_requests_total Completed HTTP requests.")
      appendLine("# TYPE xoboro_http_requests_total counter")
      requests.entries.sortedBy { it.key }.forEach { (key, value) ->
        append("xoboro_http_requests_total")
        append(key.labels)
        append(' ')
        appendLine(value.count.sum())
      }
      appendLine("# HELP xoboro_http_request_duration_seconds HTTP request duration.")
      appendLine("# TYPE xoboro_http_request_duration_seconds summary")
      requests.entries.sortedBy { it.key }.forEach { (key, value) ->
        append("xoboro_http_request_duration_seconds_count")
        append(key.labels)
        append(' ')
        appendLine(value.count.sum())
        append("xoboro_http_request_duration_seconds_sum")
        append(key.labels)
        append(' ')
        appendLine(value.totalNanos.sum().toDouble() / NANOS_PER_SECOND)
      }
      appendLine("# HELP xoboro_http_requests_active HTTP requests currently executing.")
      appendLine("# TYPE xoboro_http_requests_active gauge")
      append("xoboro_http_requests_active ")
      appendLine(activeRequestCount())
      appendLine("# HELP xoboro_ready Whether the runtime can serve traffic.")
      appendLine("# TYPE xoboro_ready gauge")
      append("xoboro_ready ")
      appendLine(if (ready) 1 else 0)
      appendLine("# HELP xoboro_task_queue_size Durable tasks awaiting or receiving work.")
      appendLine("# TYPE xoboro_task_queue_size gauge")
      append("xoboro_task_queue_size ")
      appendLine(taskQueueSize)
      appendLine("# HELP xoboro_task_workers Active durable-task worker threads.")
      appendLine("# TYPE xoboro_task_workers gauge")
      append("xoboro_task_workers ")
      appendLine(workerCount)
      appendLine("# HELP xoboro_uptime_seconds Process uptime observed by the HTTP runtime.")
      appendLine("# TYPE xoboro_uptime_seconds gauge")
      append("xoboro_uptime_seconds ")
      appendLine(uptimeSeconds)
    }
  }

  private data class RequestMetricKey(
    val method: String,
    val status: String,
  ) : Comparable<RequestMetricKey> {
    val labels: String
      get() = "{method=\"$method\",status=\"$status\"}"

    override fun compareTo(other: RequestMetricKey): Int =
      compareValuesBy(this, other, RequestMetricKey::method, RequestMetricKey::status)
  }

  private class RequestMetric {
    val count = LongAdder()
    val totalNanos = LongAdder()

    fun record(elapsedNanos: Long) {
      count.increment()
      totalNanos.add(elapsedNanos)
    }
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000.0
    val KNOWN_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

    fun normalizeMethod(method: String): String =
      method.uppercase(Locale.ROOT).takeIf(KNOWN_METHODS::contains) ?: "OTHER"

    fun normalizeStatus(status: Int): String =
      if (status in 100..599) "${status / 100}xx" else "unknown"
  }
}

internal fun Application.installOperationalMetrics(metrics: OperationalMetrics) {
  intercept(ApplicationCallPipeline.Monitoring) {
    val startedAtNanos = metrics.beginRequest()
    try {
      proceed()
    } finally {
      metrics.endRequest(
        method = context.request.httpMethod.value,
        status = context.response.status()?.value ?: HttpStatusCode.InternalServerError.value,
        startedAtNanos = startedAtNanos,
      )
    }
  }
}

internal fun Route.operationalMetricsRoute(
  token: String,
  metrics: OperationalMetrics,
  readiness: () -> Boolean,
  taskQueueSize: () -> Int,
  workerCount: () -> Int,
) {
  require(token.length >= MINIMUM_METRICS_TOKEN_LENGTH) {
    "Metrics token must contain at least $MINIMUM_METRICS_TOKEN_LENGTH characters"
  }
  get("/metrics") {
    if (!constantTimeBearerMatch(call.request.headers[HttpHeaders.Authorization], token)) {
      call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      call.respond(
        status = HttpStatusCode.Unauthorized,
        message = ErrorResponse(code = "unauthorized", message = "A valid metrics token is required"),
      )
      return@get
    }
    call.respondText(
      text =
        metrics.scrape(
          ready = readiness(),
          taskQueueSize = taskQueueSize(),
          workerCount = workerCount(),
        ),
      contentType = PROMETHEUS_CONTENT_TYPE,
    )
  }
}

private fun constantTimeBearerMatch(
  authorization: String?,
  token: String,
): Boolean {
  val expected = "Bearer $token".toByteArray(StandardCharsets.UTF_8)
  val actual = authorization?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
  return MessageDigest.isEqual(expected, actual)
}

internal const val MINIMUM_METRICS_TOKEN_LENGTH = 32

private val PROMETHEUS_CONTENT_TYPE =
  io.ktor.http.ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
