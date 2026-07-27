package io.xoboro.compatibility.komga.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaRoutesTest {
  @Test
  fun `formats Komga UTF-8 content disposition`() {
    assertEquals(
      "attachment; filename=\"=?UTF-8?Q?001.cbz?=\"; filename*=UTF-8''001.cbz",
      komgaContentDisposition("attachment", "001.cbz"),
    )
    assertEquals(
      "inline; filename=\"=?UTF-8?Q?Synthetic_Page.jpg?=\"; " +
        "filename*=UTF-8''Synthetic%20Page.jpg",
      komgaContentDisposition("inline", "Synthetic Page.jpg"),
    )
  }

  @Test
  fun `generates Spring shallow entity tags`() {
    assertEquals(
      "\"008d6c05a21512a79a1dfeb9d2a8f262f\"",
      byteArrayOf(1, 2, 3, 4).komgaCachedBody().entityTag,
    )
  }

  @Test
  fun `serves conditional cache validators with HTTP precedence`() =
    testApplication {
      val modified = Instant.parse("2030-01-02T03:04:05.678Z").toEpochMilli()
      var generatedBodies = 0
      application {
        routing {
          get("/cached") {
            if (call.respondNotModifiedByTimestamp(modified)) return@get
            generatedBodies += 1
            val body = "synthetic".encodeToByteArray().komgaCachedBody()
            if (call.respondNotModified(body, modified)) return@get
            call.respondBytes(body.bytes, ContentType.Text.Plain)
          }
        }
      }

      val initial = client.get("/cached")
      assertEquals(HttpStatusCode.OK, initial.status)
      assertEquals("\"05f06b85e1619b2b8b56f7b758a0a310a\"", initial.headers[HttpHeaders.ETag])
      assertEquals("Wed, 02 Jan 2030 03:04:05 GMT", initial.headers[HttpHeaders.LastModified])
      assertEquals(KOMGA_PRIVATE_REVALIDATE, initial.headers[HttpHeaders.CacheControl])
      assertEquals(1, generatedBodies)

      val weakEntityTag =
        client.get("/cached") {
          header(HttpHeaders.IfNoneMatch, "W/${initial.headers[HttpHeaders.ETag]}")
        }
      assertEquals(HttpStatusCode.NotModified, weakEntityTag.status)
      assertEquals(initial.headers[HttpHeaders.ETag], weakEntityTag.headers[HttpHeaders.ETag])
      assertEquals(2, generatedBodies)

      val timestamp =
        client.get("/cached") {
          header(HttpHeaders.IfModifiedSince, initial.headers[HttpHeaders.LastModified])
        }
      assertEquals(HttpStatusCode.NotModified, timestamp.status)
      assertEquals(2, generatedBodies)

      val entityTagPrecedence =
        client.get("/cached") {
          header(HttpHeaders.IfNoneMatch, "\"0ffffffffffffffffffffffffffffffff\"")
          header(HttpHeaders.IfModifiedSince, "Wed, 02 Jan 2031 03:04:05 GMT")
        }
      assertEquals(HttpStatusCode.OK, entityTagPrecedence.status)
      assertEquals(3, generatedBodies)
    }
}
