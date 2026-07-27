package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CompatibilityMaintenanceRequester
import io.xoboro.core.application.FontResource
import io.xoboro.core.application.FontResourceCatalog
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.ServerRelease
import io.xoboro.core.application.ServerReleaseCatalog
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.MediaItemId
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class ServerResourceRoutesTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `serves fonts info releases and durable maintenance requests`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("resources.sqlite"))).use {
        database ->
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 1 },
        )
      val maintenance = RecordingMaintenanceRequester()
      val hashes = FixedPageHashRepository()
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaServerResourceRoutes(
              fonts = SyntheticFontCatalog(),
              releases =
                ServerReleaseCatalog {
                  listOf(
                    ServerRelease(
                      version = "v1.0.0",
                      releaseDate = "2026-07-27T00:00:00Z",
                      url = "https://example.invalid/releases/v1.0.0",
                      latest = true,
                      preRelease = false,
                      description = "Synthetic release",
                    ),
                  )
                },
              maintenance = maintenance,
              pageHashes = hashes,
              applicationVersion = "1.0.0",
            )
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }

        assertEquals(
          listOf<Byte>(1, 2, 3),
          client
            .get("/api/v1/fonts/resource/Synthetic/font.woff2")
            .body<ByteArray>()
            .toList(),
        )
        assertTrue(
          client
            .get("/api/v1/fonts/resource/Synthetic/css")
            .bodyAsText()
            .contains("@font-face"),
        )
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          setOf("Synthetic"),
          client
            .get("/api/v1/fonts/families") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<Set<String>>(),
        )
        assertEquals(
          "1.0.0",
          client
            .get("/actuator/info") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<ServerInformationDto>().build.version,
        )
        assertEquals(
          "v1.0.0",
          client
            .get("/api/v1/releases") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<ReleaseDto>>().single().version,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.put("/api/v1/books/thumbnails?for_bigger_result_only=true") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(true, maintenance.biggerOnly)
        assertEquals(
          HttpStatusCode.Accepted,
          client.post("/api/v1/page-hashes/shared/delete-all") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(1, maintenance.deleted.size)
        assertEquals(
          HttpStatusCode.Accepted,
          client.post("/api/v1/page-hashes/shared/delete-match") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(hashes.match.toDto())
          }.status,
        )
        assertEquals(1, maintenance.deleted.size)
      }
    }
  }

  private class SyntheticFontCatalog : FontResourceCatalog {
    override fun families(): Set<String> = setOf("Synthetic")

    override fun resource(
      family: String,
      fileName: String,
    ): FontResource? =
      FontResource(fileName, "font/woff2", byteArrayOf(1, 2, 3))
        .takeIf { family == "Synthetic" && fileName == "font.woff2" }

    override fun css(family: String): String? =
      "@font-face { font-family: 'Synthetic'; }".takeIf { family == "Synthetic" }
  }

  private class RecordingMaintenanceRequester : CompatibilityMaintenanceRequester {
    var biggerOnly: Boolean? = null
    var deleted: List<PageHashMatch> = emptyList()

    override fun regenerateBookArtwork(forBiggerResultOnly: Boolean): Int {
      biggerOnly = forBiggerResultOnly
      return 1
    }

    override fun deleteDuplicatePages(
      hash: String,
      matches: List<PageHashMatch>,
    ): Int {
      assertEquals("shared", hash)
      deleted = matches
      return matches.size
    }
  }

  private class FixedPageHashRepository : PageHashRepository {
    val match =
      PageHashMatch(
        mediaItemId = MediaItemId("book-1"),
        sourceItemId = "file:///synthetic/book.cbz",
        pageNumber = 1,
        fileName = "001.jpg",
        fileSize = 3,
        mediaType = "image/jpeg",
      )

    override fun findKnownOrNull(hash: String): KnownPageHash? = null

    override fun findKnown(
      actions: Set<PageHashAction>,
      page: CatalogPageRequest,
    ): CatalogPage<KnownPageHash> = CatalogPage(emptyList(), 0, 20, 0)

    override fun findUnknown(page: CatalogPageRequest): CatalogPage<UnknownPageHash> =
      CatalogPage(emptyList(), 0, 20, 0)

    override fun findMatches(
      hash: String,
      page: CatalogPageRequest,
    ): CatalogPage<PageHashMatch> =
      CatalogPage(
        content = listOf(match).takeIf { hash == "shared" }.orEmpty(),
        page = 0,
        size = 20,
        totalElements = if (hash == "shared") 1 else 0,
        unpaged = page.unpaged,
      )

    override fun upsert(known: KnownPageHash) = Unit

    override fun incrementDeleteCount(
      hash: String,
      count: Int,
    ) = Unit
  }

  private fun PageHashMatch.toDto(): PageHashMatchDto =
    PageHashMatchDto(
      bookId = mediaItemId.value,
      url = sourceItemId,
      pageNumber = pageNumber,
      fileName = fileName,
      fileSize = fileSize,
      mediaType = mediaType,
    )

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-password"
    val komgaJson =
      Json {
        explicitNulls = false
      }
  }
}
