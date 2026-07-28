package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqMediaItemFingerprintIndex
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.Sha512TokenEncoder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class KoreaderSyncRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `authenticates with KOReader header and synchronizes EPUB progress`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("koreader.sqlite"))).use { database ->
      seed(database)
      var now = 10L
      val userIds = ArrayDeque(listOf("user-1", "user-2"))
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = userIds::removeFirst,
          currentTimeMillis = { now++ },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      val apiKeys =
        ApiKeyLifecycle(
          users = userRepository,
          apiKeys = JooqApiKeyRepository(database),
          tokenEncoder = Sha512TokenEncoder(),
          apiKeyIdFactory = { "key-1" },
          plainTextKeyFactory = { TOKEN },
          currentTimeMillis = { now++ },
        )
      apiKeys.create(user.id, "KOReader")
      val limitedUser =
        users.createUser(
          email = "limited@example.invalid",
          rawPassword = "SyntheticPassword2!",
          roles = setOf(UserRole.PAGE_STREAMING),
        )
      ApiKeyLifecycle(
        users = userRepository,
        apiKeys = JooqApiKeyRepository(database),
        tokenEncoder = Sha512TokenEncoder(),
        apiKeyIdFactory = { "key-2" },
        plainTextKeyFactory = { LIMITED_TOKEN },
        currentTimeMillis = { now++ },
      ).create(limitedUser.id, "Limited client")
      val books = JooqBookRepository(database)
      val analyzed = JooqBookMediaRepository(database)
      val progress =
        ReadProgressLifecycle(
          books = books,
          series = JooqSeriesRepository(database),
          media = analyzed,
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { now++ },
        )
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          books = books,
          media = analyzed,
          progress = progress,
          currentTimeMillis = { now++ },
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users, apiKeys)
          routing {
            komgaKoreaderSyncRoutes(sync)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json()
            }
          }

        assertEquals(
          HttpStatusCode.Forbidden,
          client.post("/koreader/users/create").status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/koreader/users/auth").status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/koreader/users/auth") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, LIMITED_TOKEN)
          }.status,
        )
        assertEquals(
          "OK",
          client.get("/koreader/users/auth") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.Accept, "application/vnd.koreader.v1+json")
          }.body<KoreaderUserAuthenticationDto>().authorized,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/koreader/syncs/progress/pdf-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.status,
        )

        val update =
          KoreaderDocumentProgressDto(
            document = "epub-fingerprint",
            percentage = 0.75F,
            progress = "/body/DocFragment[2]/body/p[1]/text().0",
            device = "Synthetic device",
            deviceId = "device-1",
          )
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/koreader/syncs/progress") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(update)
          }.status,
        )
        val saved =
          client.get("/koreader/syncs/progress/epub-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.body<KoreaderDocumentProgressDto>()
        assertEquals("epub-fingerprint", saved.document)
        assertEquals(1F, saved.percentage)
        assertEquals("/body/DocFragment[2].0", saved.progress)
        assertEquals("Synthetic device", saved.device)
        assertEquals("device-1", saved.deviceId)

        assertEquals(
          HttpStatusCode.BadRequest,
          client.put("/koreader/syncs/progress") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(update.copy(progress = "/body/DocFragment[99].0"))
          }.status,
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/koreader/syncs/progress/missing-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.status,
        )
      }
    }
  }

  @Test
  fun `reports conflict for duplicate fingerprints`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("duplicates.sqlite"))).use { database ->
      seed(database, duplicate = true)
      val books = JooqBookRepository(database)
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          books = books,
          media = JooqBookMediaRepository(database),
          progress =
            ReadProgressLifecycle(
              books = books,
              series = JooqSeriesRepository(database),
              media = JooqBookMediaRepository(database),
              progresses = JooqReadProgressRepository(database),
              currentTimeMillis = { 20 },
            ),
          currentTimeMillis = { 20 },
        )
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 10 },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")

      assertEquals(
        KoreaderProgressResult.Conflict,
        sync.find("epub-fingerprint", user),
      )
    }
  }

  private fun seed(
    database: XoboroDatabase,
    duplicate: Boolean = false,
  ) {
    val libraryId = LibraryId("library-1")
    val seriesId = SeriesId("series-1")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Synthetic library",
        root = SourceLocation("local", "root"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = seriesId,
        libraryId = libraryId,
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "series",
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      ),
    )
    val books = JooqBookRepository(database)
    val media = JooqBookMediaRepository(database)
    fun insertBook(
      id: String,
      kind: MediaKind,
      fingerprint: String,
    ) {
      books.insert(
        Book(
          id = BookId(id),
          libraryId = libraryId,
          seriesId = seriesId,
          name = id,
          relativePath = "series/$id",
          sourceItemId = "series/$id",
          mediaKind = kind,
          fileModifiedAtMillis = 1,
          fileHashKoreader = fingerprint,
          createdAtMillis = 1,
        ),
      )
    }
    insertBook("epub-1", MediaKind.EPUB, "epub-fingerprint")
    insertBook("pdf-1", MediaKind.PDF, "pdf-fingerprint")
    if (duplicate) insertBook("epub-2", MediaKind.EPUB, "epub-fingerprint")

    media.upsert(
      BookMedia(
        bookId = BookId("epub-1"),
        status = MediaStatus.READY,
        mediaType = "application/epub+zip",
        profile = MediaProfile.EPUB,
        pageCount = 2,
        files =
          listOf(
            MediaFile("chapter-1.xhtml", "application/xhtml+xml", kind = MediaFileKind.EPUB_PAGE),
            MediaFile("chapter-2.xhtml", "application/xhtml+xml", kind = MediaFileKind.EPUB_PAGE),
          ),
        positions =
          listOf(
            MediaPosition("chapter-1.xhtml", "application/xhtml+xml", 0F, 1, 0.5F),
            MediaPosition("chapter-2.xhtml", "application/xhtml+xml", 0F, 2, 1F),
          ),
        createdAtMillis = 1,
      ),
    )
    media.upsert(
      BookMedia(
        bookId = BookId("pdf-1"),
        status = MediaStatus.READY,
        mediaType = "application/pdf",
        profile = MediaProfile.PDF,
        pageCount = 2,
        createdAtMillis = 1,
      ),
    )
  }

  private companion object {
    const val TOKEN: String = "0123456789abcdef0123456789abcdef"
    const val LIMITED_TOKEN: String = "fedcba9876543210fedcba9876543210"
  }
}
