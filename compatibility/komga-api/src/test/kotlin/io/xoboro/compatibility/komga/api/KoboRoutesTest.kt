package io.xoboro.compatibility.komga.api

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.MediaSyncLifecycle
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.ProcessedArtwork
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqArtworkRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqMediaSyncSnapshotRepository
import io.xoboro.server.persistence.JooqReadListRepository
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqSyncPointRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class KoboRoutesTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `authenticates syncs and serves Kobo media contracts`() {
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("kobo.sqlite"))).use { database ->
      seed(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-admin" },
          currentTimeMillis = { 10 },
        )
      val user = users.claimInitialAdministrator(ADMIN_EMAIL, ADMIN_PASSWORD)
      val apiKeys =
        ApiKeyLifecycle(
          users = JooqUserRepository(database),
          apiKeys = JooqApiKeyRepository(database),
          tokenEncoder = TokenEncoder { it },
          apiKeyIdFactory = { "key-1" },
          plainTextKeyFactory = { KOBO_TOKEN },
          currentTimeMillis = { 20 },
        )
      assertEquals(KOBO_TOKEN, apiKeys.create(user.id, "Synthetic Kobo")?.plainTextKey)
      val catalog = JooqCatalogReadRepository(database)
      val readProgress =
        ReadProgressLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          media = JooqBookMediaRepository(database),
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { 30 },
        )
      var syncSequence = 0
      val sync =
        MediaSyncLifecycle(
          syncPoints = JooqSyncPointRepository(database),
          snapshots = JooqMediaSyncSnapshotRepository(database),
          catalog = catalog,
          readLists = JooqReadListRepository(database),
          syncPointIdFactory = { "sync-${++syncSequence}" },
          currentTimeMillis = { 40L + syncSequence },
        )
      val artwork =
        ArtworkLifecycle(
          artwork = JooqArtworkRepository(database),
          processor = {
            ProcessedArtwork(it, "image/jpeg", 1, 1)
          },
          idFactory = { "artwork-1" },
          currentTimeMillis = { 50 },
        )

      testApplication {
        application {
          install(ContentNegotiation) {
            json(JSON)
          }
          routing {
            komgaKoboRoutes(
              apiKeys = apiKeys,
              sync = sync,
              catalog = catalog,
              progress = readProgress,
              artwork = artwork,
              content = SyntheticContent(),
              kepub = SyntheticKepub(),
              syncItemLimit = 1,
            )
          }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/kobo/invalid/ping").status)
        assertEquals("pong", client.get("/kobo/$KOBO_TOKEN/ping").bodyAsText())

        val initialization = client.get("/kobo/$KOBO_TOKEN/v1/initialization")
        assertEquals(HttpStatusCode.OK, initialization.status)
        assertEquals("e30=", initialization.headers["x-kobo-apitoken"])
        assertTrue(initialization.bodyAsText().contains("\"library_sync\""))

        val authentication =
          client.post("/kobo/$KOBO_TOKEN/v1/auth/device") {
            jsonBody(JSON.encodeToString(KoboDeviceAuthRequestDto("synthetic-user-key")))
          }
        assertEquals(HttpStatusCode.OK, authentication.status)
        assertEquals(
          "synthetic-user-key",
          JSON.decodeFromString<KoboAuthDto>(authentication.bodyAsText()).userKey,
        )

        val firstSync = client.get("/kobo/$KOBO_TOKEN/v1/library/sync")
        assertEquals(HttpStatusCode.OK, firstSync.status)
        assertEquals("continue", firstSync.headers["x-kobo-sync"])
        assertEquals(1, JSON.parseToJsonElement(firstSync.bodyAsText()).jsonArray.size)
        val firstToken = assertNotNull(firstSync.headers["x-kobo-synctoken"])

        val secondSync =
          client.get("/kobo/$KOBO_TOKEN/v1/library/sync") {
            header("x-kobo-synctoken", firstToken)
          }
        assertEquals(HttpStatusCode.OK, secondSync.status)
        assertNull(secondSync.headers["x-kobo-sync"])
        assertEquals(1, JSON.parseToJsonElement(secondSync.bodyAsText()).jsonArray.size)
        val completeToken = assertNotNull(secondSync.headers["x-kobo-synctoken"])

        val unchanged =
          client.get("/kobo/$KOBO_TOKEN/v1/library/sync") {
            header("x-kobo-synctoken", completeToken)
          }
        assertEquals(JsonArray(emptyList()), JSON.parseToJsonElement(unchanged.bodyAsText()))
        val baselineToken = assertNotNull(unchanged.headers["x-kobo-synctoken"])

        val metadata =
          JSON.parseToJsonElement(
            client.get("/kobo/$KOBO_TOKEN/v1/library/book-1/metadata").bodyAsText(),
          ).jsonArray.single().jsonObject
        assertEquals("Synthetic chapter", metadata.getValue("Title").jsonPrimitive.content)
        val download = metadata.getValue("DownloadUrls").jsonArray.single().jsonObject
        assertEquals("KEPUB", download.getValue("Format").jsonPrimitive.content)
        assertTrue(download.getValue("Url").jsonPrimitive.content.endsWith("convert_kepub=true"))

        val update =
          KoboReadingStateUpdateDto(
            readingStates =
              listOf(
                KoboReadingStateDto(
                  currentBookmark =
                    KoboBookmarkDto(
                      lastModified = "2026-01-01T00:00:00Z",
                      progressPercent = 50F,
                      contentSourceProgressPercent = 50F,
                      location = KoboLocationDto(source = "chapter.xhtml"),
                    ),
                  entitlementId = BOOK_ID.value,
                  lastModified = "2026-01-01T00:00:00Z",
                  statistics = KoboStatisticsDto("2026-01-01T00:00:00Z"),
                  statusInfo =
                    KoboStatusInfoDto(
                      lastModified = "2026-01-01T00:00:00Z",
                      status = KoboStatusDto.READING,
                    ),
                ),
              ),
          )
        val updateResponse =
          client.put("/kobo/$KOBO_TOKEN/v1/library/book-1/state") {
            jsonBody(JSON.encodeToString(update))
          }
        assertEquals(HttpStatusCode.OK, updateResponse.status)
        assertEquals(
          KoboResultDto.SUCCESS,
          JSON.decodeFromString<KoboRequestResultDto>(updateResponse.bodyAsText()).requestResult,
        )

        val state =
          JSON.parseToJsonElement(
            client.get("/kobo/$KOBO_TOKEN/v1/library/book-1/state").bodyAsText(),
          ).jsonArray.single().jsonObject
        assertEquals(
          "Reading",
          state.getValue("StatusInfo").jsonObject.getValue("Status").jsonPrimitive.content,
        )
        val progressSync =
          client.get("/kobo/$KOBO_TOKEN/v1/library/sync") {
            header("x-kobo-synctoken", baselineToken)
          }
        val progressChange =
          JSON.parseToJsonElement(progressSync.bodyAsText()).jsonArray.single().jsonObject
        assertTrue("ChangedReadingState" in progressChange)
        val progressToken = assertNotNull(progressSync.headers["x-kobo-synctoken"])

        assertContentEquals(
          SyntheticContent.BOOK_BYTES,
          client.get("/kobo/$KOBO_TOKEN/v1/books/book-1/file/epub").bodyAsText().encodeToByteArray(),
        )
        assertContentEquals(
          SyntheticKepub.BYTES,
          client
            .get("/kobo/$KOBO_TOKEN/v1/books/book-1/file/epub?convert_kepub=true")
            .bodyAsText()
            .encodeToByteArray(),
        )
        val thumbnail =
          client.get("/kobo/$KOBO_TOKEN/v1/books/book-1/thumbnail/300/400/false/image.jpg")
        assertContentEquals(
          SyntheticContent.IMAGE_BYTES,
          thumbnail.bodyAsText().encodeToByteArray(),
        )
        val thumbnailEntityTag = assertNotNull(thumbnail.headers[HttpHeaders.ETag])
        assertEquals(
          HttpStatusCode.NotModified,
          client.get("/kobo/$KOBO_TOKEN/v1/books/book-1/thumbnail/300/400/false/image.jpg") {
            header(HttpHeaders.IfNoneMatch, thumbnailEntityTag)
          }.status,
        )

        val books = JooqBookRepository(database)
        books.update(
          requireNotNull(books.findByIdOrNull(BOOK_ID)).copy(
            deletedAtMillis = 60,
            updatedAtMillis = 60,
          ),
        )
        val removalSync =
          client.get("/kobo/$KOBO_TOKEN/v1/library/sync") {
            header("x-kobo-synctoken", progressToken)
          }
        assertEquals("continue", removalSync.headers["x-kobo-sync"])
        assertTrue(
          "ChangedEntitlement" in
            JSON.parseToJsonElement(removalSync.bodyAsText()).jsonArray.single().jsonObject,
        )
        val removalToken = assertNotNull(removalSync.headers["x-kobo-synctoken"])
        val shelfRemoval =
          client.get("/kobo/$KOBO_TOKEN/v1/library/sync") {
            header("x-kobo-synctoken", removalToken)
          }
        assertTrue(
          "DeletedTag" in
            JSON.parseToJsonElement(shelfRemoval.bodyAsText()).jsonArray.single().jsonObject,
        )

        listOf(
          client.get("/kobo/$KOBO_TOKEN/v1/synthetic"),
          client.post("/kobo/$KOBO_TOKEN/v1/synthetic"),
          client.put("/kobo/$KOBO_TOKEN/v1/synthetic"),
          client.patch("/kobo/$KOBO_TOKEN/v1/synthetic"),
          client.delete("/kobo/$KOBO_TOKEN/v1/synthetic"),
        ).forEach { assertEquals(HttpStatusCode.OK, it.status) }
      }
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(value: String) {
    header(HttpHeaders.ContentType, "application/json")
    setBody(value)
  }

  private fun seed(database: XoboroDatabase) {
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
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "file:///synthetic/series",
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
        relativePath = "series/chapter.epub",
        sourceItemId = "file:///synthetic/series/chapter.epub",
        mediaKind = MediaKind.EPUB,
        fileModifiedAtMillis = 1,
        fileSize = SyntheticContent.BOOK_BYTES.size.toLong(),
        number = 1,
        createdAtMillis = 1,
      ),
    )
    JooqBookMediaRepository(database).upsert(
      BookMedia(
        bookId = BOOK_ID,
        status = MediaStatus.READY,
        mediaType = "application/epub+zip",
        profile = MediaProfile.EPUB,
        pageCount = 2,
        files =
          listOf(
            MediaFile(
              fileName = "chapter.xhtml",
              mediaType = "application/xhtml+xml",
              kind = MediaFileKind.EPUB_PAGE,
            ),
          ),
        positions =
          listOf(
            MediaPosition(
              href = "chapter.xhtml",
              mediaType = "application/xhtml+xml",
              progression = 0F,
              position = 1,
              totalProgression = 0F,
            ),
            MediaPosition(
              href = "chapter.xhtml",
              mediaType = "application/xhtml+xml",
              progression = 1F,
              position = 2,
              totalProgression = 1F,
            ),
          ),
        createdAtMillis = 1,
      ),
    )
    JooqReadListRepository(database).insert(
      ReadList(
        id = ReadListId("read-list-1"),
        name = "Synthetic shelf",
        bookIds = listOf(BOOK_ID),
        createdAtMillis = 1,
      ),
    )
  }

  private class SyntheticContent : BookContentAccess {
    override fun pages(bookId: BookId): List<BookPage> = emptyList()

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? =
      if (bookId == BOOK_ID && pageNumber == 1) ByteStream(IMAGE_BYTES, "image/jpeg") else null

    override fun openBook(bookId: BookId): MediaContentStream? =
      if (bookId == BOOK_ID) ByteStream(BOOK_BYTES, "application/epub+zip") else null

    companion object {
      val BOOK_BYTES = "synthetic-epub".encodeToByteArray()
      val IMAGE_BYTES = "synthetic-image".encodeToByteArray()
    }
  }

  private class SyntheticKepub : KepubContentAccess {
    override fun isAvailable(): Boolean = true

    override fun openKepub(
      bookId: BookId,
      revision: String,
    ): MediaContentStream? =
      if (bookId == BOOK_ID && revision.isNotBlank()) {
        ByteStream(BYTES, "application/epub+zip")
      } else {
        null
      }

    companion object {
      val BYTES = "synthetic-kepub".encodeToByteArray()
    }
  }

  private class ByteStream(
    private val bytes: ByteArray,
    override val mediaType: String,
  ) : MediaContentStream {
    private var cursor = 0
    override val fileName: String = "synthetic.bin"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (cursor >= bytes.size) return -1
      val count = minOf(length, bytes.size - cursor)
      bytes.copyInto(buffer, offset, cursor, cursor + count)
      cursor += count
      return count
    }

    override fun close() = Unit
  }

  private companion object {
    val LIBRARY_ID = LibraryId("library-1")
    val SERIES_ID = SeriesId("series-1")
    val BOOK_ID = BookId("book-1")
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "SyntheticPassword1!"
    const val KOBO_TOKEN = "synthetic-kobo-token"
    val JSON =
      Json {
        ignoreUnknownKeys = true
        explicitNulls = false
      }
  }
}
