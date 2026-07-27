package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class GithubReleaseCatalogTest {
  @Test
  fun `maps and caches GitHub releases for one hour`() =
    runBlocking {
      var requests = 0
      var now = 1_000L
      val client =
        HttpClient(
          MockEngine {
            requests += 1
            respond(
              content =
                """
                [
                  {
                    "tag_name": "v1.0.0",
                    "published_at": "2026-07-27T00:00:00Z",
                    "html_url": "https://example.invalid/v1.0.0",
                    "prerelease": false,
                    "body": "Synthetic release",
                    "ignored": true
                  }
                ]
                """.trimIndent(),
              headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
          },
        ) {
          install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
          }
        }
      client.use {
        val catalog =
          GithubReleaseCatalog(
            client = client,
            currentTimeMillis = { now },
            endpoint = "https://example.invalid/releases",
          )

        val first = catalog.releases().single()
        now += 1_000
        val second = catalog.releases().single()

        assertEquals("v1.0.0", first.version)
        assertEquals(true, first.latest)
        assertEquals(first, second)
        assertEquals(1, requests)
      }
    }
}
