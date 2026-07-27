package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.patch
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
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqPageHashRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqReadListRepository
import io.xoboro.server.persistence.JooqSeriesCollectionRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class CatalogRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `serves paged catalog detail siblings and series groups`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("catalog-api.sqlite"))).use {
        database ->
      seedCatalog(database)
      val booksRepository = JooqBookRepository(database)
      (1..2).forEach { number ->
        val book = requireNotNull(booksRepository.findByIdOrNull(BookId("book-$number")))
        booksRepository.update(book.copy(fileHash = "shared-file", fileSize = 1_024))
      }
      var userSequence = 0
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-${++userSequence}" },
          currentTimeMillis = { 10 },
        )
      val catalog = JooqCatalogReadRepository(database)
      val content = SyntheticBookContentAccess()
      val pageHashes = JooqPageHashRepository(database)
      val pageHashLifecycle = PageHashLifecycle(pageHashes) { 40 }
      val collections = JooqSeriesCollectionRepository(database)
      val readLists = JooqReadListRepository(database)
      var organizationSequence = 0
      val organizations =
        OrganizationLifecycle(
          collections = collections,
          readLists = readLists,
          series = JooqSeriesRepository(database),
          books = JooqBookRepository(database),
          collectionIdFactory = { "collection-${++organizationSequence}" },
          readListIdFactory = { "read-list-${++organizationSequence}" },
          currentTimeMillis = { 30 + organizationSequence.toLong() },
        )
      val progress =
        ReadProgressLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          media = JooqBookMediaRepository(database),
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { 20 },
        )
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(KOMGA_JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaCatalogRoutes(catalog)
            komgaMediaRoutes(catalog, content)
            komgaPageHashRoutes(pageHashes, pageHashLifecycle, content)
            komgaReadProgressRoutes(catalog, progress)
            komgaWebPubRoutes(catalog, progress)
            komgaOrganizationRoutes(collections, readLists, organizations, catalog)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(KOMGA_JSON)
            }
          }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        users.createUser(
          email = RESTRICTED_EMAIL,
          rawPassword = RESTRICTED_PASSWORD,
          roles = emptySet<UserRole>(),
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/api/v1/books/book-1/pages/1") {
            basicAuth(RESTRICTED_EMAIL, RESTRICTED_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/api/v1/books/book-1/file") {
            basicAuth(RESTRICTED_EMAIL, RESTRICTED_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.post("/api/v1/collections") {
            basicAuth(RESTRICTED_EMAIL, RESTRICTED_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              CollectionCreationDto(
                name = "Denied collection",
                ordered = true,
                seriesIds = listOf("series-1"),
              ),
            )
          }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/books").status)
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v1/books/book-1/pages").status,
        )
        assertEquals(
          HttpStatusCode.Unauthorized,
          client.get("/api/v1/books/book-1/manifest").status,
        )

        val pages =
          client
            .get("/api/v1/books/book-1/pages") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<KomgaPageContentDto>>()
        assertEquals("001.png", pages.single().fileName)
        assertEquals(320, pages.single().width)
        assertEquals("4 B", pages.single().size)

        assertEquals(
          listOf<Byte>(1, 2, 3, 4),
          client
            .get("/api/v1/books/book-1/pages/1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<ByteArray>().toList(),
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-1/pages/0?zero_based=true") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-1/pages/1/raw") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-1/pages/1?convert=jpeg") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.BadRequest,
          client.get("/api/v1/books/book-1/pages/1?convert=gif") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-1/pages/1/thumbnail") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/api/v1/books/missing/pages/1") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        val downloaded =
          client.get("/api/v1/books/book-1/file") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, downloaded.status)
        assertEquals(listOf<Byte>(10, 20, 30, 40, 50), downloaded.body<ByteArray>().toList())
        assertTrue(
          downloaded.headers[HttpHeaders.ContentDisposition]
            .orEmpty()
            .contains("synthetic.cbz"),
        )
        val ranged =
          client.get("/api/v1/books/book-1/file/archive.cbz") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.Range, "bytes=1-3")
          }
        assertEquals(HttpStatusCode.PartialContent, ranged.status)
        assertEquals("bytes 1-3/5", ranged.headers[HttpHeaders.ContentRange])
        assertEquals(listOf<Byte>(20, 30, 40), ranged.body<ByteArray>().toList())
        assertEquals(
          HttpStatusCode.RequestedRangeNotSatisfiable,
          client.get("/api/v1/books/book-1/file") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.Range, "bytes=20-30")
          }.status,
        )
        assertEquals(8, content.closedStreams)

        val manifestResponse =
          client.get("/api/v1/books/book-1/manifest") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, manifestResponse.status)
        assertEquals(
          "application/divina+json",
          manifestResponse.headers[HttpHeaders.ContentType],
        )
        val manifest =
          KOMGA_JSON.decodeFromString<WPPublicationDto>(manifestResponse.bodyAsText())
        assertEquals("Synthetic chapter 1", manifest.metadata.title)
        assertEquals("rtl", manifest.metadata.readingProgression)
        assertEquals(2, manifest.metadata.numberOfPages)
        assertEquals(2, manifest.readingOrder.size)
        assertTrue(manifest.readingOrder.first().href.orEmpty().contains("/pages/1"))
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/books/book-1/manifest/divina") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.get("/api/v1/books/book-1/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        val progression =
          R2ProgressionDto(
            modified = "2030-01-02T03:04:05Z",
            device = R2DeviceDto(id = "synthetic-device", name = "Synthetic Reader"),
            locator =
              R2LocatorDto(
                href = "/api/v1/books/book-1/pages/1",
                type = "image/png",
                locations = R2LocationDto(position = 1),
              ),
          )
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/api/v1/books/book-1/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(progression)
          }.status,
        )
        val savedProgressionResponse =
          client.get("/api/v1/books/book-1/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, savedProgressionResponse.status)
        val savedProgression =
          KOMGA_JSON.decodeFromString<R2ProgressionDto>(savedProgressionResponse.bodyAsText())
        assertEquals(progression, savedProgression)
        assertEquals(
          HttpStatusCode.Conflict,
          client.put("/api/v1/books/book-1/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(progression.copy(modified = "2029-01-02T03:04:05Z"))
          }.status,
        )
        assertEquals(
          HttpStatusCode.BadRequest,
          client.put("/api/v1/books/book-1/progression") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              progression.copy(
                modified = "2031-01-02T03:04:05Z",
                locator = progression.locator.copy(locations = R2LocationDto(position = 99)),
              ),
            )
          }.status,
        )

        val duplicateBooks =
          client
            .get("/api/v1/books/duplicates") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(2, duplicateBooks.totalElements)
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/api/v1/books/duplicates") {
            basicAuth(RESTRICTED_EMAIL, RESTRICTED_PASSWORD)
          }.status,
        )
        val unknownHashes =
          client
            .get("/api/v1/page-hashes/unknown") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<PageHashUnknownDto>>()
        assertEquals(1, unknownHashes.totalElements)
        assertEquals(2, unknownHashes.content.single().matchCount)
        assertEquals(
          HttpStatusCode.OK,
          client.get("/api/v1/page-hashes/unknown/shared-page/thumbnail?resize=300") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.put("/api/v1/page-hashes") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              PageHashCreationDto(
                hash = "shared-page",
                size = 4,
                action = io.xoboro.core.domain.PageHashAction.IGNORE,
              ),
            )
          }.status,
        )
        val knownHashes =
          client
            .get("/api/v1/page-hashes?action=IGNORE") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<PageHashKnownDto>>()
        assertEquals(1, knownHashes.totalElements)
        assertEquals(2, knownHashes.content.single().matchCount)
        assertEquals(
          2,
          client
            .get("/api/v1/page-hashes/shared-page") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<PageHashMatchDto>>()
            .totalElements,
        )

        val collection =
          client
            .post("/api/v1/collections") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
              header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
              setBody(
                CollectionCreationDto(
                  name = "Synthetic collection",
                  ordered = true,
                  seriesIds = listOf("series-1"),
                ),
              )
            }.body<KomgaCollectionDto>()
        assertEquals(listOf("series-1"), collection.seriesIds)
        assertEquals(
          listOf("series-1"),
          client
            .get("/api/v1/collections/${collection.id}/series") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaSeriesDto>>().content.map(KomgaSeriesDto::id),
        )
        assertEquals(
          listOf(collection.id),
          client
            .get("/api/v1/series/series-1/collections") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<KomgaCollectionDto>>().map(KomgaCollectionDto::id),
        )
        assertEquals(
          HttpStatusCode.BadRequest,
          client.post("/api/v1/collections") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              CollectionCreationDto(
                name = "synthetic COLLECTION",
                ordered = false,
                seriesIds = listOf("series-1"),
              ),
            )
          }.status,
        )
        seedRestrictedSeries(database)
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/collections/${collection.id}") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(CollectionUpdateDto(seriesIds = listOf("series-1", "series-2")))
          }.status,
        )
        users.updateUser(
          requireNotNull(users.findByEmailIgnoreCaseOrNull(RESTRICTED_EMAIL)).copy(
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(LibraryId("library-1")),
          ),
        )
        val filteredCollection =
          client
            .get("/api/v1/collections/${collection.id}") {
              basicAuth(RESTRICTED_EMAIL, RESTRICTED_PASSWORD)
            }.body<KomgaCollectionDto>()
        assertEquals(listOf("series-1"), filteredCollection.seriesIds)
        assertTrue(filteredCollection.filtered)

        val readList =
          client
            .post("/api/v1/readlists") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
              header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
              setBody(
                ReadListCreationDto(
                  name = "Synthetic reading order",
                  summary = "Synthetic summary",
                  ordered = true,
                  bookIds = listOf("book-2", "book-1"),
                ),
              )
            }.body<KomgaReadListDto>()
        assertEquals(listOf("book-2", "book-1"), readList.bookIds)
        assertEquals(
          listOf("book-2", "book-1"),
          client
            .get("/api/v1/readlists/${readList.id}/books") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>().content.map(KomgaBookDto::id),
        )
        assertEquals(
          "book-1",
          client
            .get("/api/v1/readlists/${readList.id}/books/book-2/next") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaBookDto>().id,
        )
        assertEquals(
          "book-2",
          client
            .get("/api/v1/readlists/${readList.id}/books/book-1/previous") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaBookDto>().id,
        )
        assertEquals(
          listOf(readList.id),
          client
            .get("/api/v1/books/book-1/readlists") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<KomgaReadListDto>>().map(KomgaReadListDto::id),
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/readlists/${readList.id}") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ReadListUpdateDto(name = "Updated synthetic list"))
          }.status,
        )
        assertEquals(
          "Updated synthetic list",
          client
            .get("/api/v1/readlists/${readList.id}") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaReadListDto>().name,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/readlists/${readList.id}") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/collections/${collection.id}") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )

        val books =
          client
            .get("/api/v1/books?size=1&sort=numberSort,desc") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(2, books.totalElements)
        assertEquals(1, books.content.size)
        assertEquals("book-2", books.content.single().id)
        assertEquals("Synthetic catalog", books.content.single().seriesTitle)
        assertEquals("READY", books.content.single().media.status)

        val previous =
          client
            .get("/api/v1/books/book-2/previous") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaBookDto>()
        assertEquals("book-1", previous.id)
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/api/v1/books/book-2/next") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )

        val series =
          client
            .get("/api/v1/series/series-1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaSeriesDto>()
        assertEquals("Synthetic catalog", series.metadata.title)
        assertEquals(2, series.booksCount)

        val seriesBooks =
          client
            .get("/api/v1/series/series-1/books") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(listOf("book-1", "book-2"), seriesBooks.content.map(KomgaBookDto::id))

        val groups =
          client
            .get("/api/v1/series/alphabetical-groups") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<List<KomgaGroupCountDto>>()
        assertEquals(
          listOf(
            KomgaGroupCountDto("R", 1),
            KomgaGroupCountDto("S", 1),
          ),
          groups,
        )

        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/books/book-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ReadProgressUpdateDto(completed = true))
          }.status,
        )
        val onDeck =
          client
            .get("/api/v1/books/ondeck") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaPageDto<KomgaBookDto>>()
        assertEquals(listOf("book-2"), onDeck.content.map(KomgaBookDto::id))
        assertTrue(
          client
            .get("/api/v1/books/book-1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaBookDto>().readProgress?.completed == true,
        )
        assertEquals(
          HttpStatusCode.BadRequest,
          client.patch("/api/v1/books/book-2/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ReadProgressUpdateDto(page = 99))
          }.status,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.post("/api/v1/series/series-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          2,
          client
            .get("/api/v1/series/series-1") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<KomgaSeriesDto>().booksReadCount,
        )
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/series/series-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )

        assertEquals(
          HttpStatusCode.BadRequest,
          client.post("/api/v1/series/list") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(buildJsonObject { put("condition", buildJsonObject { put("type", "all") }) })
          }.status,
        )
      }
    }
  }

  private fun seedCatalog(database: XoboroDatabase) {
    val libraryId = LibraryId("library-1")
    val seriesId = SeriesId("series-1")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = seriesId,
        libraryId = libraryId,
        name = "Synthetic catalog",
        relativePath = "synthetic-catalog",
        sourceItemId = "file:///synthetic/synthetic-catalog",
        fileModifiedAtMillis = 2,
        bookCount = 2,
        createdAtMillis = 1,
      ),
    )
    val books = JooqBookRepository(database)
    repeat(2) { index ->
      val number = index + 1
      books.insert(
        Book(
          id = BookId("book-$number"),
          libraryId = libraryId,
          seriesId = seriesId,
          name = "Synthetic chapter $number",
          relativePath = "synthetic-catalog/chapter-$number.cbz",
          sourceItemId = "file:///synthetic/synthetic-catalog/chapter-$number.cbz",
          mediaKind = MediaKind.COMIC_ARCHIVE,
          fileModifiedAtMillis = number.toLong(),
          fileSize = number * 1_024L,
          number = number,
          createdAtMillis = number.toLong(),
        ),
      )
    }
    val seriesMetadata = JooqSeriesMetadataRepository(database)
    seriesMetadata.upsert(
      seriesMetadata.findBySeriesIdOrNull(seriesId)!!.copy(
        title = "Synthetic catalog",
        titleSort = "Synthetic catalog",
        readingDirection = ReadingDirection.RIGHT_TO_LEFT,
        updatedAtMillis = 2,
      ),
    )
    repeat(2) { index ->
      JooqBookMediaRepository(database).upsert(
        BookMedia(
          bookId = BookId("book-${index + 1}"),
          status = MediaStatus.READY,
          mediaType = "application/zip",
          profile = MediaProfile.DIVINA,
          pages =
            listOf(
              BookPage(
                number = 1,
                fileName = "001.png",
                mediaType = "image/png",
                fileSize = 4,
                fileHash = "shared-page",
              ),
              BookPage(
                number = 2,
                fileName = "002.png",
                mediaType = "image/png",
                fileSize = 4,
                fileHash = "unique-page-${index + 1}",
              ),
            ),
          pageCount = 2,
          createdAtMillis = 1,
        ),
      )
    }
    assertTrue(JooqCatalogReadRepository(database).findBookByIdOrNull(BookId("book-1"), io.xoboro.core.application.CatalogAccess()) != null)
  }

  private fun seedRestrictedSeries(database: XoboroDatabase) {
    val libraryId = LibraryId("library-2")
    val seriesId = SeriesId("series-2")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Restricted synthetic library",
        root = SourceLocation("local", "file:///restricted-synthetic"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = seriesId,
        libraryId = libraryId,
        name = "Restricted synthetic catalog",
        relativePath = "restricted-synthetic-catalog",
        sourceItemId = "file:///restricted-synthetic/restricted-synthetic-catalog",
        fileModifiedAtMillis = 2,
        bookCount = 0,
        createdAtMillis = 1,
      ),
    )
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "SyntheticPassword1!"
    const val RESTRICTED_EMAIL = "restricted@example.invalid"
    const val RESTRICTED_PASSWORD = "SyntheticPassword2!"
    val KOMGA_JSON = Json { explicitNulls = false }
  }

  private class SyntheticBookContentAccess : BookContentAccess {
    var closedStreams: Int = 0

    override fun pages(bookId: BookId): List<BookPage>? =
      if (bookId == BookId("book-1")) {
        listOf(
          BookPage(
            number = 1,
            fileName = "001.png",
            mediaType = "image/png",
            fileSize = 4,
            dimension = Dimension(320, 640),
          ),
        )
      } else {
        null
      }

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? {
      if (bookId != BookId("book-1") || pageNumber != 1) return null
      return object : MediaContentStream {
        private val bytes = byteArrayOf(1, 2, 3, 4)
        private var cursor = 0
        override val mediaType: String = "image/png"
        override val contentLength: Long = bytes.size.toLong()

        override fun read(
          buffer: ByteArray,
          offset: Int,
          length: Int,
        ): Int {
          if (cursor == bytes.size) return -1
          val count = minOf(length, bytes.size - cursor)
          bytes.copyInto(buffer, offset, cursor, cursor + count)
          cursor += count
          return count
        }

        override fun close() {
          closedStreams += 1
        }
      }
    }

    override fun openBook(bookId: BookId): MediaContentStream? {
      if (bookId != BookId("book-1")) return null
      return object : MediaContentStream {
        private val bytes = byteArrayOf(10, 20, 30, 40, 50)
        private var cursor = 0
        override val fileName: String = "synthetic.cbz"
        override val mediaType: String = "application/zip"
        override val contentLength: Long = bytes.size.toLong()

        override fun read(
          buffer: ByteArray,
          offset: Int,
          length: Int,
        ): Int {
          if (cursor == bytes.size) return -1
          val count = minOf(length, bytes.size - cursor)
          bytes.copyInto(buffer, offset, cursor, cursor + count)
          cursor += count
          return count
        }

        override fun skip(byteCount: Long): Long {
          val count = minOf(byteCount.toInt(), bytes.size - cursor)
          cursor += count
          return count.toLong()
        }

        override fun close() {
          closedStreams += 1
        }
      }
    }
  }
}
