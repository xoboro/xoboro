package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.HttpClient
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
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.OrganizationLifecycle
import io.xoboro.core.application.OrganizationEvent
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.SequentialReadProgressLifecycle
import io.xoboro.core.domain.Author
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
import io.xoboro.server.persistence.JooqBookMetadataRepository
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
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class CatalogRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `reports requested sorting and explicit null catalog fields`() {
    val unsorted =
      CatalogPage(content = listOf("item"), page = 0, size = 20, totalElements = 1)
        .toPageDto(listOf("item"))
    assertTrue(unsorted.sort.empty)
    assertFalse(unsorted.sort.sorted)
    assertTrue(unsorted.sort.unsorted)

    val sorted =
      CatalogPage(
        content = listOf("item"),
        page = 0,
        size = 20,
        totalElements = 1,
        sorts = listOf(CatalogSort("title")),
      ).toPageDto(listOf("item"))
    assertFalse(sorted.sort.empty)
    assertTrue(sorted.sort.sorted)
    assertFalse(sorted.sort.unsorted)

    val wire =
      KOMGA_CATALOG_RESPONSE_JSON.encodeToString(
        KomgaBookMetadataAggregationDto(
          authors = emptyList(),
          tags = emptySet(),
          releaseDate = null,
          summary = "",
          summaryNumber = "",
          created = "2030-01-01T00:00:00Z",
          lastModified = "2030-01-01T00:00:00Z",
        ),
      )
    assertTrue(wire.contains("\"releaseDate\":null"))
  }

  @Test
  fun `honors deprecated book and series filters`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("deprecated-filters.sqlite"))).use {
        database ->
      seedCatalog(database)
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "filter-admin" },
          currentTimeMillis = { 10 },
        )
      val catalog = JooqCatalogReadRepository(database)
      val progress =
        ReadProgressLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          media = JooqBookMediaRepository(database),
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { 20 },
        )
      val collections = JooqSeriesCollectionRepository(database)
      val organizations =
        OrganizationLifecycle(
          collections = collections,
          readLists = JooqReadListRepository(database),
          series = JooqSeriesRepository(database),
          books = JooqBookRepository(database),
          collectionIdFactory = { "filter-collection" },
          readListIdFactory = { "filter-read-list" },
          currentTimeMillis = { 30 },
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
            komgaReadProgressRoutes(catalog, progress)
            komgaOrganizationRoutes(
              collections,
              JooqReadListRepository(database),
              organizations,
              catalog,
            )
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
        val collection =
          client
            .post("/api/v1/collections") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
              header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
              setBody(
                CollectionCreationDto(
                  name = "Filter collection",
                  ordered = false,
                  seriesIds = listOf("series-1"),
                ),
              )
            }.body<KomgaCollectionDto>()
        verifyDeprecatedCollectionFilter(client, collection.id)
        assertEquals(
          HttpStatusCode.NoContent,
          client.patch("/api/v1/books/book-1/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ReadProgressUpdateDto(completed = true))
          }.status,
        )
        verifyDeprecatedBookAndSeriesFilters(client)
        verifyDeprecatedFilterMismatches(client)
        verifyStructuredBookAndSeriesFilters(client)
      }
    }
  }

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
      val organizationEvents = mutableListOf<OrganizationEvent>()
      val organizations =
        OrganizationLifecycle(
          collections = collections,
          readLists = readLists,
          series = JooqSeriesRepository(database),
          books = JooqBookRepository(database),
          collectionIdFactory = { "collection-${++organizationSequence}" },
          readListIdFactory = { "read-list-${++organizationSequence}" },
          currentTimeMillis = { 30 + organizationSequence.toLong() },
          eventPublisher = organizationEvents::add,
        )
      val progress =
        ReadProgressLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          media = JooqBookMediaRepository(database),
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { 20 },
        )
      val sequentialProgress =
        SequentialReadProgressLifecycle(
          catalog = catalog,
          readLists = readLists,
          progress = progress,
        )
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(KOMGA_JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaFileSystemRoutes()
            komgaCatalogRoutes(catalog)
            komgaMediaRoutes(catalog, content)
            komgaPageHashRoutes(pageHashes, pageHashLifecycle, content)
            komgaReadProgressRoutes(catalog, progress)
            komgaTachiyomiProgressRoutes(sequentialProgress)
            komgaWebPubRoutes(catalog, progress, content)
            komgaOrganizationRoutes(collections, readLists, organizations, catalog)
            komgaArchiveRoutes(catalog, readLists, content)
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

        val browsable = tempDirectory.resolve("browsable")
        Files.createDirectories(browsable.resolve("Synthetic folder"))
        Files.writeString(browsable.resolve("synthetic.txt"), "synthetic")
        Files.writeString(browsable.resolve(".hidden.txt"), "hidden")
        val listing =
          client.post("/api/v1/filesystem") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              DirectoryRequestDto(
                path = browsable.toString(),
                showFiles = true,
              ),
            )
          }.body<DirectoryListingDto>()
        assertEquals(listOf("Synthetic folder"), listing.directories.map(PathDto::name))
        assertEquals(listOf("synthetic.txt"), listing.files.map(PathDto::name))
        assertEquals(
          HttpStatusCode.Forbidden,
          client.post("/api/v1/filesystem") {
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
          HttpStatusCode.BadRequest,
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
        assertEquals(7, content.closedStreams)
        verifyPdfPageNegotiation(client, database, content)

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
        val initialReadListProgress =
          client
            .get("/api/v1/readlists/${readList.id}/read-progress/tachiyomi") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<TachiyomiReadProgressDto>()
        assertEquals(2, initialReadListProgress.booksCount)
        assertEquals(0, initialReadListProgress.booksReadCount)
        assertEquals(1, initialReadListProgress.booksUnreadCount)
        assertEquals(1, initialReadListProgress.booksInProgressCount)
        assertEquals(0, initialReadListProgress.lastReadContinuousIndex)
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/api/v1/readlists/${readList.id}/read-progress/tachiyomi") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TachiyomiReadProgressUpdateDto(lastBookRead = 1))
          }.status,
        )
        val updatedReadListProgress =
          client
            .get("/api/v1/readlists/${readList.id}/read-progress/tachiyomi") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<TachiyomiReadProgressDto>()
        assertEquals(1, updatedReadListProgress.booksReadCount)
        assertEquals(1, updatedReadListProgress.booksInProgressCount)
        assertEquals(1, updatedReadListProgress.lastReadContinuousIndex)
        assertEquals(
          HttpStatusCode.NoContent,
          client.delete("/api/v1/books/book-2/read-progress") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
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
        val readListArchive =
          client.get("/api/v1/readlists/${readList.id}/file") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, readListArchive.status)
        assertEquals(
          listOf("2 - synthetic.cbz"),
          readListArchive.body<ByteArray>().zipEntryNames(),
        )
        val seriesArchive =
          client.get("/api/v1/series/series-1/file") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, seriesArchive.status)
        assertEquals(
          listOf("synthetic.cbz"),
          seriesArchive.body<ByteArray>().zipEntryNames(),
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
        assertEquals(
          listOf(
            "CollectionAdded",
            "CollectionUpdated",
            "ReadListAdded",
            "ReadListUpdated",
            "ReadListDeleted",
            "CollectionDeleted",
          ),
          organizationEvents.map { it::class.simpleName },
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
        assertEquals(
          "/synthetic/synthetic-catalog/chapter-2.cbz",
          books.content.single().url,
        )
        assertEquals("READY", books.content.single().media.status)
        assertTrue(books.content.single().media.epubDivinaCompatible)
        assertTrue(books.content.single().media.epubIsKepub)

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
        assertEquals("/synthetic/synthetic-catalog", series.url)
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
            KomgaGroupCountDto("r", 1),
            KomgaGroupCountDto("s", 1),
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
        val initialSeriesProgress =
          client
            .get("/api/v2/series/series-1/read-progress/tachiyomi") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<TachiyomiReadProgressV2Dto>()
        assertEquals(2, initialSeriesProgress.booksCount)
        assertEquals(2, initialSeriesProgress.booksUnreadCount)
        assertEquals(0F, initialSeriesProgress.lastReadContinuousNumberSort)
        assertEquals(2F, initialSeriesProgress.maxNumberSort)
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/api/v2/series/series-1/read-progress/tachiyomi") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TachiyomiReadProgressUpdateV2Dto(lastBookNumberSortRead = 1F))
          }.status,
        )
        val updatedSeriesProgress =
          client
            .get("/api/v2/series/series-1/read-progress/tachiyomi") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            }.body<TachiyomiReadProgressV2Dto>()
        assertEquals(1, updatedSeriesProgress.booksReadCount)
        assertEquals(1, updatedSeriesProgress.booksUnreadCount)
        assertEquals(1F, updatedSeriesProgress.lastReadContinuousNumberSort)

        val structuredSeries =
          client
            .post("/api/v1/series/list") {
              basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
              header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
              setBody(
                Json.parseToJsonElement(
                  """
                  {
                    "condition": {
                      "allOf": [
                        {"libraryId": {"operator": "is", "value": "library-1"}},
                        {"title": {"operator": "contains", "value": "catalog"}},
                        {"oneShot": {"operator": "isFalse"}}
                      ]
                    }
                  }
                  """.trimIndent(),
                ),
              )
            }.body<KomgaPageDto<KomgaSeriesDto>>()
        assertEquals(listOf("series-1"), structuredSeries.content.map(KomgaSeriesDto::id))

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

  private suspend fun verifyDeprecatedCollectionFilter(
    client: HttpClient,
    collectionId: String,
  ) {
    assertEquals(
      listOf("series-1"),
      client
        .get("/api/v1/series?collection_id=$collectionId") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
        }.body<KomgaPageDto<KomgaSeriesDto>>().content.map(KomgaSeriesDto::id),
    )
  }

  private suspend fun verifyDeprecatedBookAndSeriesFilters(client: HttpClient) {
    assertEquals(
      listOf("book-1"),
      client
        .get(
          "/api/v1/books" +
            "?media_status=READY&read_status=READ&tag=sample&released_after=2024-12-31",
        ) {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
        }.body<KomgaPageDto<KomgaBookDto>>().content.map(KomgaBookDto::id),
    )
    assertEquals(
      listOf("book-2"),
      client
        .get("/api/v1/books?media_status=READY&tag=sample&released_after=2025-12-31") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
        }.body<KomgaPageDto<KomgaBookDto>>().content.map(KomgaBookDto::id),
    )
    assertEquals(
      listOf("series-1"),
      client
        .get(
          "/api/v1/series" +
            "?status=ONGOING&read_status=IN_PROGRESS&age_rating=13" +
            "&release_year=2025&complete=true&sharing_label=sample-access" +
            "&publisher=Synthetic%20Publisher&language=en&genre=Adventure" +
            "&tag=sample&author=Synthetic%20Author,writer",
        ) {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
        }.body<KomgaPageDto<KomgaSeriesDto>>().content.map(KomgaSeriesDto::id),
    )
  }

  private suspend fun verifyDeprecatedFilterMismatches(client: HttpClient) {
    listOf(
      "/api/v1/books?media_status=ERROR",
      "/api/v1/books?read_status=IN_PROGRESS",
      "/api/v1/books?tag=missing",
      "/api/v1/books?released_after=2026-12-31",
      "/api/v1/series?collection_id=missing",
      "/api/v1/series?status=ENDED",
      "/api/v1/series?read_status=READ",
      "/api/v1/series?publisher=Other",
      "/api/v1/series?language=fr",
      "/api/v1/series?genre=Drama",
      "/api/v1/series?tag=missing",
      "/api/v1/series?age_rating=18",
      "/api/v1/series?release_year=2024",
      "/api/v1/series?sharing_label=missing",
      "/api/v1/series?complete=false",
      "/api/v1/series?author=Other,writer",
    ).forEach { path ->
      assertEquals(
        0,
        client
          .get(path) {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.body<KomgaPageDto<JsonObject>>().totalElements,
        path,
      )
    }
  }

  private suspend fun verifyStructuredBookAndSeriesFilters(client: HttpClient) {
    val books =
      client
        .post("/api/v1/books/list?sort=metadata.numberSort,desc") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            Json.parseToJsonElement(
              """
              {
                "condition": {
                  "allOf": [
                    {"tag": {"operator": "is", "value": "sample"}},
                    {"numberSort": {"operator": "greaterThan", "value": 1}},
                    {"title": {"operator": "contains", "value": "chapter"}},
                    {"mediaStatus": {"operator": "is", "value": "READY"}},
                    {"mediaProfile": {"operator": "is", "value": "DIVINA"}},
                    {
                      "author": {
                        "operator": "is",
                        "value": {"name": "Synthetic Author", "role": "writer"}
                      }
                    }
                  ]
                }
              }
              """.trimIndent(),
            ),
          )
        }.body<KomgaPageDto<KomgaBookDto>>()
    assertEquals(listOf("book-2"), books.content.map(KomgaBookDto::id))

    val series =
      client
        .post("/api/v1/series/list?sort=metadata.titleSort,asc") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(
            Json.parseToJsonElement(
              """
              {
                "condition": {
                  "allOf": [
                    {"publisher": {"operator": "is", "value": "synthetic publisher"}},
                    {"language": {"operator": "is", "value": "en"}},
                    {"genre": {"operator": "is", "value": "Adventure"}},
                    {"tag": {"operator": "is", "value": "sample"}},
                    {"ageRating": {"operator": "greaterThan", "value": 10}},
                    {"complete": {"operator": "isTrue"}},
                    {"seriesStatus": {"operator": "is", "value": "ONGOING"}},
                    {
                      "releaseDate": {
                        "operator": "after",
                        "dateTime": "2024-12-31T00:00:00Z"
                      }
                    },
                    {
                      "author": {
                        "operator": "is",
                        "value": {"name": "Synthetic Author", "role": "writer"}
                      }
                    }
                  ]
                }
              }
              """.trimIndent(),
            ),
          )
        }.body<KomgaPageDto<KomgaSeriesDto>>()
    assertEquals(listOf("series-1"), series.content.map(KomgaSeriesDto::id))
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
        publisher = "Synthetic Publisher",
        ageRating = 13,
        language = "en",
        genres = setOf("Adventure"),
        tags = setOf("sample"),
        totalBookCount = 2,
        sharingLabels = setOf("sample-access"),
        readingDirection = ReadingDirection.RIGHT_TO_LEFT,
        updatedAtMillis = 2,
      ),
    )
    val bookMetadata = JooqBookMetadataRepository(database)
    repeat(2) { index ->
      val bookId = BookId("book-${index + 1}")
      bookMetadata.upsert(
        requireNotNull(bookMetadata.findByBookIdOrNull(bookId)).copy(
          summary = "Synthetic summary ${index + 1}",
          releaseDate = if (index == 0) "2025-01-02" else "2026-02-03",
          authors = listOf(Author("Synthetic Author", "writer")),
          tags = setOf("sample"),
          updatedAtMillis = 2,
        ),
      )
      JooqBookMediaRepository(database).upsert(
        BookMedia(
          bookId = bookId,
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
          epubDivinaCompatible = index == 1,
          epubIsKepub = index == 1,
          createdAtMillis = 1,
        ),
      )
    }
    assertTrue(JooqCatalogReadRepository(database).findBookByIdOrNull(BookId("book-1"), io.xoboro.core.application.CatalogAccess()) != null)
  }

  private suspend fun verifyPdfPageNegotiation(
    client: HttpClient,
    database: XoboroDatabase,
    content: SyntheticBookContentAccess,
  ) {
    val mediaRepository = JooqBookMediaRepository(database)
    val originalSecondMedia =
      requireNotNull(mediaRepository.findByBookIdOrNull(BookId("book-2")))
    mediaRepository.upsert(
      originalSecondMedia.copy(
        mediaType = "application/pdf",
        profile = MediaProfile.PDF,
      ),
    )
    try {
      val negotiatedPdf =
        client.get("/api/v1/books/book-2/pages/1") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          header(HttpHeaders.Accept, "application/pdf")
        }
      assertEquals(HttpStatusCode.OK, negotiatedPdf.status)
      assertEquals("application/pdf", negotiatedPdf.headers[HttpHeaders.ContentType])
      assertTrue(content.lastPageRequest?.raw == true)

      val preferredImage =
        client.get("/api/v1/books/book-2/pages/1") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          header(HttpHeaders.Accept, "image/jpeg, application/pdf")
        }
      assertEquals("image/png", preferredImage.headers[HttpHeaders.ContentType])
      assertFalse(content.lastPageRequest?.raw == true)

      val disabledNegotiation =
        client.get("/api/v1/books/book-2/pages/1?contentNegotiation=false") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          header(HttpHeaders.Accept, "application/pdf")
        }
      assertEquals("image/png", disabledNegotiation.headers[HttpHeaders.ContentType])
      assertFalse(content.lastPageRequest?.raw == true)

      assertEquals(
        HttpStatusCode.BadRequest,
        client.get("/api/v1/books/book-2/pages/1?contentNegotiation=invalid") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
        }.status,
      )
      assertEquals(
        HttpStatusCode.OK,
        client.get("/api/v1/books/book-2/pages/1/raw?contentNegotiation=invalid") {
          basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
        }.status,
      )
    } finally {
      mediaRepository.upsert(originalSecondMedia)
    }
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

  private fun ByteArray.zipEntryNames(): List<String> =
    ZipInputStream(ByteArrayInputStream(this)).use { archive ->
      buildList {
        while (true) {
          val entry = archive.nextEntry ?: break
          add(entry.name)
          archive.closeEntry()
        }
      }
    }

  private class SyntheticBookContentAccess : BookContentAccess {
    var closedStreams: Int = 0
    var lastPageRequest: PageImageRequest? = null

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
      if (bookId !in setOf(BookId("book-1"), BookId("book-2")) || pageNumber != 1) return null
      lastPageRequest = request
      return object : MediaContentStream {
        private val bytes = byteArrayOf(1, 2, 3, 4)
        private var cursor = 0
        override val mediaType: String = if (request.raw) "application/pdf" else "image/png"
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
