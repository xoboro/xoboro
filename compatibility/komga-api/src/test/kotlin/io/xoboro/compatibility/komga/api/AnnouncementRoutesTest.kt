package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.AnnouncementLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.AnnouncementAuthor
import io.xoboro.core.domain.AnnouncementFeed
import io.xoboro.core.domain.AnnouncementFeedProvider
import io.xoboro.core.domain.AnnouncementItem
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqAnnouncementReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class AnnouncementRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `returns administrator feed with durable per-user read state`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("announcements.sqlite"))).use {
        database ->
      val users = JooqUserRepository(database)
      val ids = listOf("admin-1", "reader-1").iterator()
      val userLifecycle =
        UserLifecycle(
          users = users,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = ids::next,
          currentTimeMillis = { 1_000 },
        )
      val feedProvider = MutableFeedProvider(syntheticFeed())
      val announcements =
        AnnouncementLifecycle(
          feedProvider = feedProvider,
          reads = JooqAnnouncementReadRepository(database),
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(userLifecycle)
          routing {
            komgaClaimRoutes(userLifecycle)
            komgaAuthenticatedUserRoutes(
              users = userLifecycle,
              libraries = JooqLibraryRepository(database),
            )
            komgaAnnouncementRoutes(announcements)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        client.post("/api/v1/claim") {
          header("X-Komga-Email", ADMIN_EMAIL)
          header("X-Komga-Password", ADMIN_PASSWORD)
        }
        userLifecycle.createUser(READER_EMAIL, READER_PASSWORD)

        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v1/announcements").status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/api/v1/announcements") {
            readerCredentials()
          }.status,
        )
        val unread =
          client.get("/api/v1/announcements") {
            adminCredentials()
          }.body<JsonFeedDto>()
        assertEquals("Synthetic announcements", unread.title)
        assertFalse(unread.items.single().komgaExtension!!.read)
        assertEquals("Synthetic author", unread.items.single().author?.name)

        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/api/v1/announcements") {
            adminCredentials()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(setOf("announcement-1"))
          }.status,
        )
        val read =
          client.get("/api/v1/announcements") {
            adminCredentials()
          }.body<JsonFeedDto>()
        assertTrue(read.items.single().komgaExtension!!.read)

        feedProvider.feed = null
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/api/v1/announcements") {
            adminCredentials()
          }.status,
        )
      }
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.adminCredentials() {
    basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.readerCredentials() {
    basicAuth(READER_EMAIL, READER_PASSWORD)
  }

  private class MutableFeedProvider(
    var feed: AnnouncementFeed?,
  ) : AnnouncementFeedProvider {
    override suspend fun fetch(): AnnouncementFeed? = feed
  }

  private fun syntheticFeed(): AnnouncementFeed =
    AnnouncementFeed(
      version = "https://jsonfeed.org/version/1",
      title = "Synthetic announcements",
      homePageUrl = "https://example.invalid/announcements",
      items =
        listOf(
          AnnouncementItem(
            id = "announcement-1",
            title = "Synthetic release",
            author =
              AnnouncementAuthor(
                name = "Synthetic author",
                url = "https://example.invalid/author",
              ),
            tags = setOf("release"),
          ),
        ),
    )

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val READER_EMAIL = "reader@example.invalid"
    const val READER_PASSWORD = "synthetic-reader-password"
    val komgaJson = Json { explicitNulls = false }
  }
}
