package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryEventPublisher
import io.xoboro.core.application.LibraryLifecycle
import io.xoboro.core.application.LibraryMaintenanceQueue
import io.xoboro.core.application.LibraryRootAccess
import io.xoboro.core.application.RootType
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeCatalogTest {
  @Test
  fun `returns only authorized native resources and preserves catalog filters`() =
    testApplication {
      val fixture = Fixture.restricted()
      installCatalog(fixture)

      val libraries =
        client
          .get("$XOBORO_API_PREFIX/libraries") {
            bearerAuth(fixture.token)
          }.body<List<XoboroLibraryResponse>>()
      assertEquals(listOf("library-visible"), libraries.map(XoboroLibraryResponse::id))
      assertNull(libraries.single().source)

      val series =
        client
          .get(
            "$XOBORO_API_PREFIX/series?query=synthetic&genre=adventure" +
              "&page=1&size=5&sort=updatedAt,desc",
          ) {
            bearerAuth(fixture.token)
          }.body<XoboroPageResponse<XoboroSeriesResponse>>()
      assertEquals("Synthetic series", series.items.single().title)
      assertEquals(1, series.page)
      assertEquals(5, series.size)
      assertEquals(setOf("adventure"), fixture.catalog.lastSeriesQuery?.genres)
      assertEquals("synthetic", fixture.catalog.lastSeriesQuery?.fullTextSearch)
      assertEquals("lastModified", fixture.catalog.lastPage?.sorts?.single()?.property)
      assertEquals(
        io.xoboro.core.application.CatalogSortDirection.DESC,
        fixture.catalog.lastPage?.sorts?.single()?.direction,
      )
      assertEquals(setOf(LibraryId("library-visible")), fixture.catalog.lastAccess?.libraryIds)

      val media =
        client
          .get("$XOBORO_API_PREFIX/media-items?sort=number,asc") {
            bearerAuth(fixture.token)
          }.body<XoboroPageResponse<XoboroMediaItemResponse>>()
      assertEquals("COMIC", media.items.single().type)
      assertEquals("Synthetic issue", media.items.single().title)
      assertEquals("numberSort", fixture.catalog.lastPage?.sorts?.single()?.property)
    }

  @Test
  fun `shows source locations only to administrators`() =
    testApplication {
      val fixture = Fixture.administrator()
      installCatalog(fixture)

      val library =
        client
          .get("$XOBORO_API_PREFIX/libraries/library-visible") {
            bearerAuth(fixture.token)
          }.body<XoboroLibraryResponse>()

      assertEquals("local", library.source?.provider)
      assertEquals("file:///synthetic", library.source?.location)
    }

  @Test
  fun `rejects invalid native pagination before querying storage`() =
    testApplication {
      val fixture = Fixture.administrator()
      installCatalog(fixture)

      val response =
        client.get("$XOBORO_API_PREFIX/series?size=201") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("invalid_query", response.body<XoboroApiError>().code)
      assertFalse(fixture.catalog.queried)
    }

  private fun ApplicationTestBuilder.installCatalog(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        exception<XoboroInvalidQueryException> { call, cause ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_query", requireNotNull(cause.message)),
          )
        }
      }
      routing {
        xoboroNativeCatalogRoutes(fixture.libraries, fixture.catalog)
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private class Fixture private constructor(
    user: User,
  ) {
    private val users = InMemoryUserRepository(user)
    private val libraryRepository =
      InMemoryLibraryRepository(
        listOf(
          syntheticLibrary("library-visible", "Visible library"),
          syntheticLibrary("library-hidden", "Hidden library"),
        ),
      )
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "catalog-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = sessions.create(user).plainToken
    val libraries =
      LibraryAdministrationLifecycle(
        libraries = libraryRepository,
        lifecycle =
          LibraryLifecycle(
            repository = libraryRepository,
            rootAccess =
              object : LibraryRootAccess {
                override fun typeOf(root: SourceLocation): RootType = RootType.DIRECTORY

                override fun isReadable(root: SourceLocation): Boolean = true

                override fun isSameOrAncestor(
                  possibleAncestor: SourceLocation,
                  possibleDescendant: SourceLocation,
                ): Boolean = possibleAncestor == possibleDescendant
              },
            maintenanceQueue = NoOpLibraryMaintenanceQueue,
            eventPublisher = LibraryEventPublisher {},
          ),
        libraryIdFactory = { "unused-library" },
        currentTimeMillis = { 1_000 },
      )
    val catalog = RecordingCatalog()

    companion object {
      fun restricted(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.PAGE_STREAMING),
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(LibraryId("library-visible")),
          ),
        )

      fun administrator(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.ADMIN),
            sharesAllLibraries = true,
          ),
        )
    }
  }

  private class RecordingCatalog : CatalogReadRepository {
    var queried = false
    var lastSeriesQuery: SeriesCatalogQuery? = null
    var lastAccess: CatalogAccess? = null
    var lastPage: CatalogPageRequest? = null

    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> {
      queried = true
      lastAccess = access
      lastPage = page
      return CatalogPage(listOf(syntheticBook()), page.page, page.size, 1, sorts = page.sorts)
    }

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = syntheticBook().takeIf { it.book.id == id }

    override fun findPreviousBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findNextBookOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? = null

    override fun findSeries(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogSeries> {
      queried = true
      lastSeriesQuery = query
      lastAccess = access
      lastPage = page
      return CatalogPage(listOf(syntheticSeries()), page.page, page.size, 1, sorts = page.sorts)
    }

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? = syntheticSeries().takeIf { it.series.id == id }

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ) = emptyList<io.xoboro.core.application.CatalogGroupCount>()
  }

  private class InMemoryLibraryRepository(
    libraries: List<Library>,
  ) : LibraryRepository {
    private val values = libraries.associateByTo(linkedMapOf(), Library::id)

    override fun findById(id: LibraryId): Library = requireNotNull(values[id])

    override fun findByIdOrNull(id: LibraryId): Library? = values[id]

    override fun findAll(): List<Library> = values.values.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> = ids.mapNotNull(values::get)

    override fun insert(library: Library) {
      values[library.id] = library
    }

    override fun update(library: Library) {
      values[library.id] = library
    }

    override fun delete(id: LibraryId) {
      values.remove(id)
    }

    override fun deleteAll() {
      values.clear()
    }

    override fun count(): Long = values.size.toLong()
  }

  private class InMemoryUserRepository(
    user: User,
  ) : UserRepository {
    private var value: User? = user

    override fun count(): Long = if (value == null) 0 else 1

    override fun findByIdOrNull(id: UserId): User? = value?.takeIf { it.id == id }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      value?.takeIf { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = listOfNotNull(value)

    override fun insert(user: User) {
      if (value != null) throw UserEmailAlreadyExistsException(user.email)
      value = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (value != null) return false
      value = user
      return true
    }

    override fun update(user: User) {
      value = user
    }

    override fun delete(id: UserId) {
      if (value?.id == id) value = null
    }
  }

  private object NoOpLibraryMaintenanceQueue : LibraryMaintenanceQueue {
    override fun scanLibrary(id: LibraryId) = Unit

    override fun reschedulePeriodicScan(library: Library) = Unit

    override fun hashBooksWithoutFileHash(id: LibraryId) = Unit

    override fun hashBooksWithoutKoreaderHash(id: LibraryId) = Unit

    override fun hashBooksWithMissingPageHash(id: LibraryId) = Unit

    override fun repairExtensions(id: LibraryId) = Unit

    override fun convertBooksToCbz(id: LibraryId) = Unit
  }

  companion object {
    private fun syntheticUser(
      roles: Set<UserRole>,
      sharesAllLibraries: Boolean,
      sharedLibraryIds: Set<LibraryId> = emptySet(),
    ): User =
      User(
        id = UserId("user-1"),
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        createdAtMillis = 1,
      )

    private fun syntheticLibrary(
      id: String,
      name: String,
    ): Library =
      Library(
        id = LibraryId(id),
        name = name,
        root = SourceLocation("local", "file:///synthetic"),
        createdAtMillis = 1,
      )

    private fun syntheticSeries(): CatalogSeries {
      val id = SeriesId("series-1")
      return CatalogSeries(
        series =
          Series(
            id = id,
            libraryId = LibraryId("library-visible"),
            name = "Synthetic series",
            relativePath = "Synthetic series",
            sourceItemId = "file:///synthetic/series",
            fileModifiedAtMillis = 2,
            bookCount = 1,
            createdAtMillis = 1,
          ),
        metadata =
          SeriesMetadata(
            seriesId = id,
            title = "Synthetic series",
            genres = setOf("adventure"),
            createdAtMillis = 1,
          ),
        booksMetadata =
          io.xoboro.core.application.BookMetadataAggregation(
            createdAtMillis = 1,
            updatedAtMillis = 1,
          ),
        readProgress = null,
      )
    }

    private fun syntheticBook(): CatalogBook {
      val id = BookId("media-1")
      val series = syntheticSeries()
      return CatalogBook(
        book =
          Book(
            id = id,
            libraryId = LibraryId("library-visible"),
            seriesId = series.series.id,
            name = "Synthetic issue.cbz",
            relativePath = "Synthetic series/Synthetic issue.cbz",
            sourceItemId = "file:///synthetic/series/issue.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 2,
            fileSize = 100,
            number = 1,
            createdAtMillis = 1,
          ),
        seriesTitle = series.metadata.title,
        seriesMetadata = series.metadata,
        metadata =
          BookMetadata(
            bookId = id,
            title = "Synthetic issue",
            number = "1",
            numberSort = 1F,
            createdAtMillis = 1,
          ),
        media =
          BookMedia(
            bookId = id,
            status = MediaStatus.READY,
            mediaType = "application/vnd.comicbook+zip",
            pageCount = 1,
            createdAtMillis = 1,
          ),
        readProgress = null,
      )
    }
  }
}
