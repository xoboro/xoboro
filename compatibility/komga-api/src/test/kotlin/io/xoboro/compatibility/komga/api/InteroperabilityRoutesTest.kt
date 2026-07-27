package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ReadListImportLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.SyncPoint
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.UserId
import io.xoboro.server.metadata.ComicRackReadListParser
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqHistoricalEventRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqReadListImportMatcher
import io.xoboro.server.persistence.JooqReadListRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqSyncPointRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class InteroperabilityRoutesTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `serves history ComicRack matching and scoped sync deletion`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("interoperability.sqlite"))).use {
        database ->
      seedCatalog(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { USER_ID.value },
          currentTimeMillis = { 10 },
        )
      val history = JooqHistoricalEventRepository(database)
      history.insert(
        HistoricalEvent(
          id = "event-1",
          type = "BookImported",
          timestampMillis = 20,
          bookId = BOOK_ID,
          seriesId = SERIES_ID,
          properties = mapOf("name" to "Synthetic chapter"),
        ),
      )
      val syncPoints = JooqSyncPointRepository(database)
      val imports =
        ReadListImportLifecycle(
          parser = ComicRackReadListParser(),
          matcher = JooqReadListImportMatcher(database),
          readLists = JooqReadListRepository(database),
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(komgaJson)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaHistoryRoutes(history)
            komgaSyncPointRoutes(syncPoints)
            komgaComicRackRoutes(imports)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(komgaJson)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )

        val historyPage =
          client
            .get("/api/v1/history?sort=timestamp,desc") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<HistoricalEventDto>>()
        assertEquals(1, historyPage.totalElements)
        assertEquals("BookImported", historyPage.content.single().type)
        assertEquals("book-7", historyPage.content.single().bookId)
        assertEquals(
          "Synthetic chapter",
          historyPage.content.single().properties["name"],
        )

        val matchResponse =
          client.post("/api/v1/readlists/match/comicrack") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            setBody(
              MultiPartFormDataContent(
                formData {
                  append(
                    key = "file",
                    value = comicRackList(),
                    headers =
                      Headers.build {
                        append(
                          HttpHeaders.ContentDisposition,
                          ContentDisposition.File
                            .withParameter(ContentDisposition.Parameters.Name, "file")
                            .withParameter(ContentDisposition.Parameters.FileName, "synthetic.cbl")
                            .toString(),
                        )
                        append(HttpHeaders.ContentType, ContentType.Application.Xml.toString())
                      },
                  )
                },
              ),
            )
          }
        assertEquals(HttpStatusCode.OK, matchResponse.status)
        val match = matchResponse.body<ReadListRequestMatchDto>()
        assertEquals("Synthetic reading order", match.readListMatch.name)
        assertEquals(
          listOf("book-7"),
          match.requests.single().matches.single().books.map { it.bookId },
        )

        val keys = JooqApiKeyRepository(database)
        keys.insert(apiKey(API_KEY_ONE, "first-hash", "First synthetic client"))
        keys.insert(apiKey(API_KEY_TWO, "second-hash", "Second synthetic client"))
        val first = syncPoint("sync-first", API_KEY_ONE)
        val second = syncPoint("sync-second", API_KEY_TWO)
        syncPoints.insert(first)
        syncPoints.insert(second)
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/syncpoints/me?key_id=${API_KEY_ONE.value}") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertNull(syncPoints.findByIdOrNull(first.id))
        assertNotNull(syncPoints.findByIdOrNull(second.id))
      }
    }
  }

  private fun seedCatalog(database: XoboroDatabase) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = SERIES_ID,
        libraryId = LIBRARY_ID,
        name = "Synthetic catalog",
        relativePath = "catalog",
        sourceItemId = "file:///synthetic/catalog",
        fileModifiedAtMillis = 1,
        bookCount = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookRepository(database).insert(
      Book(
        id = BOOK_ID,
        libraryId = LIBRARY_ID,
        seriesId = SERIES_ID,
        name = "Synthetic chapter",
        relativePath = "catalog/chapter.cbz",
        sourceItemId = "file:///synthetic/catalog/chapter.cbz",
        mediaKind = MediaKind.COMIC_ARCHIVE,
        fileModifiedAtMillis = 1,
        number = 7,
        createdAtMillis = 1,
      ),
    )
  }

  private fun comicRackList(): ByteArray =
    """
    <ReadingList>
      <Name>Synthetic reading order</Name>
      <Books>
        <Book Series="Synthetic catalog" Number="007" />
      </Books>
    </ReadingList>
    """.trimIndent().encodeToByteArray()

  private fun apiKey(
    id: ApiKeyId,
    hash: String,
    comment: String,
  ): ApiKey =
    ApiKey(
      id = id,
      userId = USER_ID,
      keyHash = hash,
      comment = comment,
      createdAtMillis = 30,
    )

  private fun syncPoint(
    id: String,
    apiKeyId: ApiKeyId,
  ): SyncPoint =
    SyncPoint(
      id = SyncPointId(id),
      userId = USER_ID,
      apiKeyId = apiKeyId,
      createdAtMillis = 40,
    )

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-password"
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-7")
    val USER_ID = UserId("user-1")
    val API_KEY_ONE = ApiKeyId("key-1")
    val API_KEY_TWO = ApiKeyId("key-2")
    val komgaJson =
      Json {
        explicitNulls = false
      }
  }
}
