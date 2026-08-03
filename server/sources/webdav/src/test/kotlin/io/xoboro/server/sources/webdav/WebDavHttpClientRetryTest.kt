package io.xoboro.server.sources.webdav

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A remote source's transport is expected to blink; the task queue's retry budget is not sized
 * for that. Against a real library a dropped tunnel turned 13,983 `ANALYZE_BOOK` tasks DEAD at
 * 3/3 attempts, permanently, even though every archive was reachable again seconds later. The
 * adapter absorbs a transient failure so the queue never sees one, for the same reason the
 * SQLite lock is absorbed in the persistence layer: the knowledge belongs where the flakiness is.
 */
class WebDavHttpClientRetryTest {
  @Test
  fun `retries a connection failure and succeeds once the server is listening`() {
    // A port with nothing behind it fails to connect. The server starts accepting only after
    // the first attempt, so a client that gives up immediately can never succeed here.
    val socket = ServerSocket(0)
    val port = socket.localPort
    socket.close()

    val attempts = AtomicInteger()
    val client =
      WebDavHttpClient(
        maximumTransportAttempts = 4,
        retryBackoffMillis = 20,
        beforeAttempt = { if (attempts.incrementAndGet() == 1) Unit else startAcceptingOn(port) },
      )

    val resources = client.propfind("http://127.0.0.1:$port/", credentials = null, depth = 1)
    assertTrue(attempts.get() >= 2, "expected more than one attempt, made ${attempts.get()}")
    assertEquals(1, resources.size, "the retried request returned the fake server's response")
  }

  @Test
  fun `gives up after the configured number of attempts and names the cause`() {
    val socket = ServerSocket(0)
    val port = socket.localPort
    socket.close()

    val attempts = AtomicInteger()
    val client =
      WebDavHttpClient(
        maximumTransportAttempts = 3,
        retryBackoffMillis = 5,
        beforeAttempt = { attempts.incrementAndGet() },
      )

    val failure =
      assertFailsWith<WebDavRequestFailedException> {
        client.propfind("http://127.0.0.1:$port/", credentials = null, depth = 1)
      }

    assertEquals(3, attempts.get())
    // "status -1" on its own told an operator nothing: every one of those 13,983 dead tasks
    // recorded exactly that and no reason. The transport failure has to reach the message.
    assertTrue(
      failure.message.orEmpty().contains("Connection refused", ignoreCase = true) ||
        failure.message.orEmpty().contains("ConnectException", ignoreCase = true),
      "message did not name the cause: ${failure.message}",
    )
  }

  private fun startAcceptingOn(port: Int) {
    Thread {
      runCatching {
        ServerSocket(port).use { server ->
          server.accept().use { connection ->
            connection.getInputStream().read(ByteArray(1_024))
            val body =
              ("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">" +
                "<D:response><D:href>/</D:href><D:propstat>" +
                "<D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
                "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>" +
                "</D:multistatus>").toByteArray()
            // Length computed, not written by hand: a wrong Content-Length truncates the body
            // and the failure then looks like a parser defect rather than a fixture mistake.
            connection.getOutputStream().write(
              "HTTP/1.1 207 Multi-Status\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
            )
          }
        }
      }
    }.apply { isDaemon = true }.start()
    Thread.sleep(50)
  }
}
