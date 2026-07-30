package io.xoboro.server

import kotlin.test.Test
import kotlin.test.assertEquals

class OperationalMetricsTest {
  @Test
  fun `tracks active and total request counts across concurrent and completed requests`() {
    val metrics = OperationalMetrics(nanoTime = { 0 })

    val firstStart = metrics.beginRequest()
    val secondStart = metrics.beginRequest()
    assertEquals(2, metrics.activeRequestCount())

    metrics.endRequest(method = "GET", status = 200, startedAtNanos = firstStart)
    assertEquals(1, metrics.activeRequestCount())
    assertEquals(1, metrics.totalRequestCount())

    metrics.endRequest(method = "POST", status = 404, startedAtNanos = secondStart)
    assertEquals(0, metrics.activeRequestCount())
    assertEquals(2, metrics.totalRequestCount())
  }

  @Test
  fun `groups completed requests by status class`() {
    val metrics = OperationalMetrics(nanoTime = { 0 })

    metrics.endRequest("GET", 200, metrics.beginRequest())
    metrics.endRequest("GET", 201, metrics.beginRequest())
    metrics.endRequest("GET", 404, metrics.beginRequest())
    metrics.endRequest("GET", 500, metrics.beginRequest())

    assertEquals(
      mapOf("2xx" to 2L, "4xx" to 1L, "5xx" to 1L),
      metrics.requestCountsByStatusClass(),
    )
  }

  @Test
  fun `reports uptime from the injected clock`() {
    var now = 0L
    val metrics = OperationalMetrics(nanoTime = { now })

    now = 2_500_000_000L
    assertEquals(2.5, metrics.uptimeSeconds())
  }

  @Test
  fun `scrape output is consistent with the accessor methods`() {
    val metrics = OperationalMetrics(nanoTime = { 0 })
    metrics.endRequest("GET", 200, metrics.beginRequest())

    val scraped = metrics.scrape(ready = true, taskQueueSize = 5, workerCount = 2)

    assertEquals(
      "xoboro_http_requests_active ${metrics.activeRequestCount()}",
      scraped.lines().first { it.startsWith("xoboro_http_requests_active") },
    )
  }
}
