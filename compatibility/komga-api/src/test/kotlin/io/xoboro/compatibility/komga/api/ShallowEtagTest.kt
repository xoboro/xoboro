package io.xoboro.compatibility.komga.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ShallowEtagTest {
  @Test
  fun `matches the five Komga download exclusions`() {
    listOf(
      "/api/v1/books/book-1/file",
      "/api/v1/books/book-1/file/archive.cbz",
      "/opds/v1.2/books/book-1/file/archive.cbz",
      "/api/v1/readlists/read-list-1/file/archive.zip",
      "/api/v1/series/series-1/file/archive.zip",
      "/kobo/token/v1/books/book-1/file/download",
    ).forEach { path ->
      assertTrue(path.isKomgaEtagExcluded(), path)
    }
    listOf(
      "/api/v1/books/book-1/pages/1",
      "/opds/v2/books/book-1/thumbnail",
      "/kobo/token/v1/books/book-1/state",
    ).forEach { path ->
      assertFalse(path.isKomgaEtagExcluded(), path)
    }
  }

  @Test
  fun `filters serialized protocol responses with Komga exclusions`() =
    testApplication {
      application {
        install(ContentNegotiation) {
          json(Json { explicitNulls = false })
        }
        installKomgaShallowEtag()
        routing {
          get("/api/v1/synthetic") {
            call.respond(SyntheticResponse("stable"))
          }
          get("/reader/opds/v2/synthetic") {
            call.respondText("feed", ContentType.Application.Json)
          }
          get("/kobo/token/v1/synthetic") {
            call.respondBytes(byteArrayOf(1, 2, 3), ContentType.Application.OctetStream)
          }
          get("/api/v1/books/book-1/file") {
            call.respondBytes(byteArrayOf(4, 5, 6), ContentType.Application.OctetStream)
          }
          get("/api/v1/no-store") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText("private")
          }
          get("/outside") {
            call.respondText("outside")
          }
        }
      }

      val initial = client.get("/api/v1/synthetic")
      assertEquals(HttpStatusCode.OK, initial.status)
      assertEquals(KOMGA_PRIVATE_REVALIDATE, initial.headers[HttpHeaders.CacheControl])
      val entityTag = requireNotNull(initial.headers[HttpHeaders.ETag])
      assertEquals(initial.bodyAsText().encodeToByteArray().komgaCachedBody().entityTag, entityTag)

      val unchanged =
        client.get("/api/v1/synthetic") {
          header(HttpHeaders.IfNoneMatch, "W/$entityTag")
        }
      assertEquals(HttpStatusCode.NotModified, unchanged.status)
      assertEquals(entityTag, unchanged.headers[HttpHeaders.ETag])
      assertEquals("", unchanged.bodyAsText())

      val stale =
        client.get("/api/v1/synthetic") {
          header(HttpHeaders.IfNoneMatch, "\"0ffffffffffffffffffffffffffffffff\"")
        }
      assertEquals(HttpStatusCode.OK, stale.status)
      assertEquals(entityTag, stale.headers[HttpHeaders.ETag])

      val opds = client.get("/reader/opds/v2/synthetic")
      val opdsEntityTag = requireNotNull(opds.headers[HttpHeaders.ETag])
      assertEquals(
        HttpStatusCode.NotModified,
        client.get("/reader/opds/v2/synthetic") {
          header(HttpHeaders.IfNoneMatch, opdsEntityTag)
        }.status,
      )

      val kobo = client.get("/kobo/token/v1/synthetic")
      val koboEntityTag = requireNotNull(kobo.headers[HttpHeaders.ETag])
      assertEquals(
        HttpStatusCode.NotModified,
        client.get("/kobo/token/v1/synthetic") {
          header(HttpHeaders.IfNoneMatch, koboEntityTag)
        }.status,
      )

      assertNull(client.get("/api/v1/books/book-1/file").headers[HttpHeaders.ETag])
      assertNull(client.get("/api/v1/no-store").headers[HttpHeaders.ETag])
      assertNull(client.get("/outside").headers[HttpHeaders.ETag])
    }

  @Serializable
  private data class SyntheticResponse(
    val value: String,
  )
}
