package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.ArtworkProcessor
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookMetadataAggregation
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.ProcessedArtwork
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkContent
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkRepository
import io.xoboro.core.domain.ArtworkType
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeArtworkTest {
  @Test
  fun `selected artwork returns cached bytes and matching entity tag returns not modified`() =
    testApplication {
      val fixture = Fixture.administrator()
      fixture.artworks.seed(syntheticContent(MEDIA_OWNER, SELECTED_ID, selected = true))
      installArtwork(fixture)

      val initial = client.get(MEDIA_SELECTED_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, initial.status)
      assertContentEquals(ARTWORK_BYTES, initial.body())
      assertEquals("image/png", initial.headers[HttpHeaders.ContentType])
      val entityTag = assertNotNull(initial.headers[HttpHeaders.ETag])
      assertTrue(entityTag.matches(Regex("\"[0-9a-f]{32}\"")))
      assertNotNull(initial.headers[HttpHeaders.LastModified])
      // A short reuse window rather than `max-age=0`. At zero every cover on a grid was a conditional
      // request, so returning to a screen of a hundred cost a hundred round trips to be told nothing
      // had changed. It cannot be indefinite either: `/artwork` answers whatever is selected now, so a
      // re-scan, an upload or a selection would go unseen. `must-revalidate` keeps a copy from being
      // served once stale, and the revalidation below still has to work.
      assertEquals(
        "max-age=300, must-revalidate, private",
        initial.headers[HttpHeaders.CacheControl],
      )
      assertNull(initial.headers[HttpHeaders.ContentDisposition])

      val revalidated =
        client.get(MEDIA_SELECTED_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.IfNoneMatch, entityTag)
        }

      assertEquals(HttpStatusCode.NotModified, revalidated.status)
      assertEquals(2, fixture.artworks.findSelectedOrNullCallCount)
      assertEquals(2, fixture.artworks.contentCallCount)
    }

  @Test
  fun `missing selected artwork returns artwork not found without another content dependency`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)

      val response = client.get(MEDIA_SELECTED_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("artwork_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.artworks.findSelectedOrNullCallCount)
      assertEquals(0, fixture.artworks.contentCallCount)
    }

  @Test
  fun `media item outside library grants is hidden before artwork lookup`() =
    testApplication {
      val fixture = Fixture.restrictedReader()
      fixture.artworks.seed(syntheticContent(MEDIA_OWNER, SELECTED_ID, selected = true))
      installArtwork(fixture)

      val response = client.get(MEDIA_SELECTED_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.catalog.findBookByIdCallCount)
      assertNoArtworkCalls(fixture.artworks)
    }

  @Test
  fun `series outside library grants is hidden before artwork lookup`() =
    testApplication {
      val fixture = Fixture.restrictedReader()
      fixture.artworks.seed(syntheticContent(SERIES_OWNER, SELECTED_ID, selected = true))
      installArtwork(fixture)

      val response = client.get(SERIES_SELECTED_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("series_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.catalog.findSeriesByIdCallCount)
      assertNoArtworkCalls(fixture.artworks)
    }

  @Test
  fun `artwork list returns native metadata without owner identifiers`() =
    testApplication {
      val fixture = Fixture.administrator()
      fixture.artworks.seed(syntheticContent(MEDIA_OWNER, SELECTED_ID, selected = true))
      fixture.artworks.seed(
        syntheticContent(
          owner = MEDIA_OWNER,
          id = ArtworkId("generated-artwork"),
          selected = false,
          type = ArtworkType.GENERATED,
        ),
      )
      installArtwork(fixture)

      val response = client.get(MEDIA_LIST_PATH) { bearerAuth(fixture.token) }

      assertEquals(HttpStatusCode.OK, response.status)
      val json = response.body<String>()
      assertEquals(
        listOf(
          XoboroArtworkResponse(
            id = SELECTED_ID.value,
            type = ArtworkType.USER_UPLOADED.name,
            selected = true,
            mediaType = "image/png",
            fileSize = ARTWORK_BYTES.size.toLong(),
            width = 400,
            height = 600,
          ),
          XoboroArtworkResponse(
            id = "generated-artwork",
            type = ArtworkType.GENERATED.name,
            selected = false,
            mediaType = "image/png",
            fileSize = ARTWORK_BYTES.size.toLong(),
            width = 400,
            height = 600,
          ),
        ),
        Json.decodeFromString<List<XoboroArtworkResponse>>(json),
      )
      assertFalse("\"bookId\"" in json)
      assertFalse("\"seriesId\"" in json)
      assertFalse("\"collectionId\"" in json)
      assertFalse("\"readListId\"" in json)
      assertFalse("\"owner\"" in json)
    }

  @Test
  fun `artwork id owned by another resource is not accessible through path owner`() =
    testApplication {
      val fixture = Fixture.administrator()
      fixture.artworks.seed(syntheticContent(FOREIGN_OWNER, FOREIGN_ID, selected = true))
      installArtwork(fixture)

      val response =
        client.get("$MEDIA_LIST_PATH/${FOREIGN_ID.value}") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("artwork_not_found", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.artworks.findByIdOrNullCallCount)
      assertEquals(0, fixture.artworks.contentCallCount)
    }

  @Test
  fun `non administrator mutations are forbidden before catalog or artwork access`() =
    testApplication {
      val fixture = Fixture.unrestrictedReader()
      installArtwork(fixture)

      val post =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          setBody("not-a-multipart-body")
        }
      val put =
        client.put("$MEDIA_LIST_PATH/${SELECTED_ID.value}/selected") {
          bearerAuth(fixture.token)
        }
      val delete =
        client.delete("$MEDIA_LIST_PATH/${SELECTED_ID.value}") {
          bearerAuth(fixture.token)
        }

      for (response in listOf(post, put, delete)) {
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(
          "artwork_administration_forbidden",
          response.body<XoboroApiError>().code,
        )
      }
      assertEquals(0, fixture.catalog.findBookByIdCallCount)
      assertNoArtworkCalls(fixture.artworks)
    }

  @Test
  fun `administrator visibility is checked before upload body and mutation lifecycle access`() =
    testApplication {
      val fixture = Fixture.restrictedAdministrator()
      installArtwork(fixture)

      val post =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          setBody(ByteArray(128 * 1_024) { 1 })
        }
      val put =
        client.put("$MEDIA_LIST_PATH/${SELECTED_ID.value}/selected") {
          bearerAuth(fixture.token)
        }
      val delete =
        client.delete("$MEDIA_LIST_PATH/${SELECTED_ID.value}") {
          bearerAuth(fixture.token)
        }

      for (response in listOf(post, put, delete)) {
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("media_item_not_found", response.body<XoboroApiError>().code)
      }
      assertEquals(3, fixture.catalog.findBookByIdCallCount)
      assertNoArtworkCalls(fixture.artworks)
      assertEquals(0, fixture.processor.callCount)
    }

  @Test
  fun `cross-site cookie upload is rejected before artwork insertion`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)

      val response =
        client.post(MEDIA_LIST_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.token)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          setBody(uploadBody(UPLOAD_BYTES))
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        response.body<XoboroApiError>().code,
      )
      assertEquals(0, fixture.catalog.findBookByIdCallCount)
      assertNoArtworkCalls(fixture.artworks)
    }

  @Test
  fun `bearer upload with cross-site origin succeeds and returns metadata`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)

      val response =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          setBody(uploadBody(UPLOAD_BYTES))
        }

      assertEquals(HttpStatusCode.Created, response.status)
      assertEquals(
        XoboroArtworkResponse(
          id = UPLOADED_ID.value,
          type = ArtworkType.USER_UPLOADED.name,
          selected = true,
          mediaType = "image/png",
          fileSize = UPLOAD_BYTES.size.toLong(),
          width = 320,
          height = 480,
        ),
        response.body(),
      )
      assertEquals(1, fixture.processor.callCount)
      assertEquals(1, fixture.artworks.insertCallCount)
    }

  @Test
  fun `upload over maximum size returns payload too large before processing`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)
      val oversized = ByteArray(ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES + 1) { 1 }

      val response =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          setBody(uploadBody(oversized))
        }

      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
      assertEquals("artwork_too_large", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.processor.callCount)
      assertEquals(0, fixture.artworks.insertCallCount)
    }

  @Test
  fun `processor rejection returns unsupported media type without insertion`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)

      val response =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          setBody(uploadBody(REJECTED_BYTES))
        }

      assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
      assertEquals("artwork_not_supported", response.body<XoboroApiError>().code)
      assertEquals(1, fixture.processor.callCount)
      assertEquals(0, fixture.artworks.insertCallCount)
    }

  @Test
  fun `missing and empty upload file parts return invalid request`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)

      val missing =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          setBody(uploadBody(null))
        }
      val empty =
        client.post(MEDIA_LIST_PATH) {
          bearerAuth(fixture.token)
          setBody(uploadBody(ByteArray(0)))
        }

      for (response in listOf(missing, empty)) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.processor.callCount)
      assertEquals(0, fixture.artworks.insertCallCount)
    }

  @Test
  fun `select and delete return no content for owned ids and not found for unknown ids`() =
    testApplication {
      val fixture = Fixture.administrator()
      fixture.artworks.seed(syntheticContent(MEDIA_OWNER, SELECTED_ID, selected = false))
      installArtwork(fixture)

      val selected =
        client.put("$MEDIA_LIST_PATH/${SELECTED_ID.value}/selected") {
          bearerAuth(fixture.token)
        }
      val deleted =
        client.delete("$MEDIA_LIST_PATH/${SELECTED_ID.value}") {
          bearerAuth(fixture.token)
        }
      val missingSelection =
        client.put("$MEDIA_LIST_PATH/missing-artwork/selected") {
          bearerAuth(fixture.token)
        }
      val missingDeletion =
        client.delete("$MEDIA_LIST_PATH/missing-artwork") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.NoContent, selected.status)
      assertEquals(HttpStatusCode.NoContent, deleted.status)
      assertEquals(HttpStatusCode.NotFound, missingSelection.status)
      assertEquals(
        "artwork_not_found",
        missingSelection.body<XoboroApiError>().code,
      )
      assertEquals(HttpStatusCode.NotFound, missingDeletion.status)
      assertEquals(
        "artwork_not_found",
        missingDeletion.body<XoboroApiError>().code,
      )
      assertEquals(2, fixture.artworks.markSelectedCallCount)
      assertEquals(3, fixture.artworks.findByIdOrNullCallCount)
      assertEquals(1, fixture.artworks.deleteCallCount)
    }

  @Test
  fun `deleting generated artwork returns conflict without repository deletion`() =
    testApplication {
      val fixture = Fixture.administrator()
      fixture.artworks.seed(
        syntheticContent(
          owner = MEDIA_OWNER,
          id = GENERATED_ID,
          selected = true,
          type = ArtworkType.GENERATED,
        ),
      )
      installArtwork(fixture)

      val response =
        client.delete("$MEDIA_LIST_PATH/${GENERATED_ID.value}") {
          bearerAuth(fixture.token)
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals(
        "artwork_delete_not_supported",
        response.body<XoboroApiError>().code,
      )
      assertEquals(1, fixture.artworks.findByIdOrNullCallCount)
      assertEquals(0, fixture.artworks.deleteCallCount)
    }

  @Test
  fun `all artwork endpoints require authentication before any lookup`() =
    testApplication {
      val fixture = Fixture.administrator()
      installArtwork(fixture)
      val requests =
        listOf(
          HttpMethod.Get to MEDIA_SELECTED_PATH,
          HttpMethod.Get to MEDIA_LIST_PATH,
          HttpMethod.Get to "$MEDIA_LIST_PATH/${SELECTED_ID.value}",
          HttpMethod.Post to MEDIA_LIST_PATH,
          HttpMethod.Put to "$MEDIA_LIST_PATH/${SELECTED_ID.value}/selected",
          HttpMethod.Delete to "$MEDIA_LIST_PATH/${SELECTED_ID.value}",
        )

      for ((method, path) in requests) {
        val response = client.request(path) { this.method = method }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
      }
      assertEquals(0, fixture.catalog.findBookByIdCallCount)
      assertEquals(0, fixture.catalog.findSeriesByIdCallCount)
      assertNoArtworkCalls(fixture.artworks)
    }

  private fun ApplicationTestBuilder.installArtwork(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        exception<BadRequestException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed request"),
          )
        }
        exception<CrossSiteRequestRejectedException> { call, cause ->
          call.respond(
            HttpStatusCode.Forbidden,
            XoboroApiError(
              CrossSiteRequestRejectedException.CODE,
              requireNotNull(cause.message),
            ),
          )
        }
        exception<XoboroInvalidQueryException> { call, cause ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_query", requireNotNull(cause.message)),
          )
        }
      }
      routing {
        xoboroNativeArtworkRoutes(fixture.catalog, fixture.lifecycle)
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
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory = { "artwork-token" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val token = requireNotNull(sessions.create(user)).plainToken
    val catalog = RecordingCatalog(syntheticBook(), syntheticSeries())
    val artworks = RecordingArtworkRepository()
    val processor = RecordingArtworkProcessor()
    val lifecycle =
      ArtworkLifecycle(
        artwork = artworks,
        processor = processor,
        idFactory = { UPLOADED_ID.value },
        currentTimeMillis = { UPDATED_AT_MILLIS },
      )

    companion object {
      fun administrator(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.ADMIN),
            sharesAllLibraries = true,
          ),
        )

      fun restrictedAdministrator(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.ADMIN),
            sharesAllLibraries = true,
            restrictions = ContentRestrictions(labelsExclude = setOf(RESTRICTED_LABEL)),
          ),
        )

      fun unrestrictedReader(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.PAGE_STREAMING),
            sharesAllLibraries = true,
          ),
        )

      fun restrictedReader(): Fixture =
        Fixture(
          syntheticUser(
            roles = setOf(UserRole.PAGE_STREAMING),
            sharesAllLibraries = false,
            sharedLibraryIds = setOf(VISIBLE_LIBRARY_ID),
          ),
        )
    }
  }

  private class RecordingArtworkProcessor : ArtworkProcessor {
    var callCount = 0
      private set

    override fun process(input: ByteArray): ProcessedArtwork {
      callCount += 1
      if (input.contentEquals(REJECTED_BYTES)) {
        throw IllegalArgumentException("Synthetic processor rejection")
      }
      return ProcessedArtwork(
        bytes = input,
        mediaType = "image/png",
        width = 320,
        height = 480,
      )
    }
  }

  private class RecordingArtworkRepository : ArtworkRepository {
    private val values = linkedMapOf<Pair<ArtworkOwner, ArtworkId>, ArtworkContent>()
    var findAllCallCount = 0
      private set
    var findByIdOrNullCallCount = 0
      private set
    var findSelectedOrNullCallCount = 0
      private set
    var contentCallCount = 0
      private set
    var insertCallCount = 0
      private set
    var replaceGeneratedCallCount = 0
      private set
    var replaceSidecarsCallCount = 0
      private set
    var markSelectedCallCount = 0
      private set
    var deleteCallCount = 0
      private set

    fun seed(content: ArtworkContent) {
      put(content)
    }

    override fun findAll(owner: ArtworkOwner): List<Artwork> {
      findAllCallCount += 1
      return values.values
        .filter { it.artwork.owner == owner }
        .map(ArtworkContent::artwork)
    }

    override fun findByIdOrNull(
      owner: ArtworkOwner,
      id: ArtworkId,
    ): Artwork? {
      findByIdOrNullCallCount += 1
      return values[owner to id]?.artwork
    }

    override fun findSelectedOrNull(owner: ArtworkOwner): Artwork? {
      findSelectedOrNullCallCount += 1
      return values.values
        .firstOrNull { it.artwork.owner == owner && it.artwork.selected }
        ?.artwork
    }

    override fun content(
      owner: ArtworkOwner,
      id: ArtworkId,
    ): ByteArray? {
      contentCallCount += 1
      return values[owner to id]?.bytes
    }

    override fun insert(content: ArtworkContent) {
      insertCallCount += 1
      put(content)
    }

    override fun replaceGenerated(content: ArtworkContent) {
      replaceGeneratedCallCount += 1
      values.entries.removeAll { (key, value) ->
        key.first == content.artwork.owner && value.artwork.type == ArtworkType.GENERATED
      }
      put(content)
    }

    override fun replaceSidecars(
      owner: ArtworkOwner,
      contents: List<ArtworkContent>,
    ) {
      replaceSidecarsCallCount += 1
      values.entries.removeAll { (key, value) ->
        key.first == owner && value.artwork.type == ArtworkType.SIDECAR
      }
      contents.forEach(::put)
    }

    override fun markSelected(
      owner: ArtworkOwner,
      id: ArtworkId,
      updatedAtMillis: Long,
    ): Boolean {
      markSelectedCallCount += 1
      val target = values[owner to id] ?: return false
      values.entries
        .filter { it.key.first == owner }
        .forEach { entry ->
          val selected = entry.key.second == id
          entry.setValue(
            entry.value.copy(
              artwork =
                entry.value.artwork.copy(
                  selected = selected,
                  updatedAtMillis = updatedAtMillis,
                ),
            ),
          )
        }
      return target.artwork.owner == owner
    }

    override fun delete(
      owner: ArtworkOwner,
      id: ArtworkId,
    ): Boolean {
      deleteCallCount += 1
      return values.remove(owner to id) != null
    }

    private fun put(content: ArtworkContent) {
      if (content.artwork.selected) {
        values.entries
          .filter { it.key.first == content.artwork.owner }
          .forEach { entry ->
            entry.setValue(
              entry.value.copy(artwork = entry.value.artwork.copy(selected = false)),
            )
          }
      }
      values[content.artwork.owner to content.artwork.id] = content
    }
  }

  private class RecordingCatalog(
    private val book: CatalogBook,
    private val series: CatalogSeries,
  ) : CatalogReadRepository {
    var findBookByIdCallCount = 0
      private set
    var findSeriesByIdCallCount = 0
      private set

    override fun findBooks(
      query: BookCatalogQuery,
      access: CatalogAccess,
      page: CatalogPageRequest,
    ): CatalogPage<CatalogBook> = error("Not used")

    override fun findBookByIdOrNull(
      id: BookId,
      access: CatalogAccess,
    ): CatalogBook? {
      findBookByIdCallCount += 1
      return book.takeIf {
        it.book.id == id &&
          access.allows(it.book.libraryId, it.seriesMetadata.sharingLabels)
      }
    }

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
    ): CatalogPage<CatalogSeries> = error("Not used")

    override fun findSeriesByIdOrNull(
      id: SeriesId,
      access: CatalogAccess,
    ): CatalogSeries? {
      findSeriesByIdCallCount += 1
      return series.takeIf {
        it.series.id == id &&
          access.allows(it.series.libraryId, it.metadata.sharingLabels)
      }
    }

    override fun countSeriesByFirstCharacter(
      query: SeriesCatalogQuery,
      access: CatalogAccess,
    ) = emptyList<io.xoboro.core.application.CatalogGroupCount>()

    private fun CatalogAccess.allows(
      libraryId: LibraryId,
      sharingLabels: Set<String>,
    ): Boolean {
      val allowedLibraries = libraryIds
      if (allowedLibraries != null && libraryId !in allowedLibraries) return false
      return restrictions.labelsExclude.intersect(sharingLabels).isEmpty()
    }
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

  companion object {
    private const val RESTRICTED_LABEL = "restricted"
    private const val CREATED_AT_MILLIS = 1_735_689_600_000
    private const val UPDATED_AT_MILLIS = 1_735_689_600_123
    private val MEDIA_ID = BookId("media-artwork")
    private val SERIES_ID = SeriesId("series-artwork")
    private val HIDDEN_LIBRARY_ID = LibraryId("library-hidden")
    private val VISIBLE_LIBRARY_ID = LibraryId("library-visible")
    private val USER_ID = UserId("user-artwork")
    private val MEDIA_OWNER = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, MEDIA_ID.value)
    private val SERIES_OWNER = ArtworkOwner(ArtworkOwnerKind.SERIES, SERIES_ID.value)
    private val FOREIGN_OWNER = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, "media-foreign")
    private val SELECTED_ID = ArtworkId("selected-artwork")
    private val FOREIGN_ID = ArtworkId("foreign-artwork")
    private val GENERATED_ID = ArtworkId("generated-artwork")
    private val UPLOADED_ID = ArtworkId("uploaded-artwork")
    private val ARTWORK_BYTES = "synthetic-artwork-bytes".encodeToByteArray()
    private val UPLOAD_BYTES = "synthetic-upload-bytes".encodeToByteArray()
    private val REJECTED_BYTES = "synthetic-rejected-format".encodeToByteArray()
    private const val MEDIA_SELECTED_PATH =
      "$XOBORO_API_PREFIX/media-items/media-artwork/artwork"
    private const val MEDIA_LIST_PATH =
      "$XOBORO_API_PREFIX/media-items/media-artwork/artworks"
    private const val SERIES_SELECTED_PATH =
      "$XOBORO_API_PREFIX/series/series-artwork/artwork"

    private fun uploadBody(bytes: ByteArray?): MultiPartFormDataContent =
      MultiPartFormDataContent(
        formData {
          if (bytes == null) {
            append("note", "synthetic upload without a file")
          } else {
            append(
              key = "file",
              value = bytes,
              headers =
                Headers.build {
                  append(
                    HttpHeaders.ContentDisposition,
                    "form-data; name=\"file\"; filename=\"artwork.png\"",
                  )
                  append(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                },
            )
          }
        },
      )

    private fun syntheticContent(
      owner: ArtworkOwner,
      id: ArtworkId,
      selected: Boolean,
      type: ArtworkType = ArtworkType.USER_UPLOADED,
    ): ArtworkContent =
      ArtworkContent(
        artwork =
          Artwork(
            id = id,
            owner = owner,
            type = type,
            selected = selected,
            mediaType = "image/png",
            fileSize = ARTWORK_BYTES.size.toLong(),
            width = 400,
            height = 600,
            createdAtMillis = CREATED_AT_MILLIS,
            updatedAtMillis = UPDATED_AT_MILLIS,
          ),
        bytes = ARTWORK_BYTES,
      )

    private fun syntheticUser(
      roles: Set<UserRole>,
      sharesAllLibraries: Boolean,
      sharedLibraryIds: Set<LibraryId> = emptySet(),
      restrictions: ContentRestrictions = ContentRestrictions(),
    ): User =
      User(
        id = USER_ID,
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        restrictions = restrictions,
        createdAtMillis = 1,
      )

    private fun syntheticSeries(): CatalogSeries {
      val metadata =
        SeriesMetadata(
          seriesId = SERIES_ID,
          title = "Synthetic artwork series",
          sharingLabels = setOf(RESTRICTED_LABEL),
          createdAtMillis = 1,
        )
      return CatalogSeries(
        series =
          Series(
            id = SERIES_ID,
            libraryId = HIDDEN_LIBRARY_ID,
            name = "Synthetic artwork series",
            relativePath = "Synthetic artwork series",
            sourceItemId = "file:///synthetic/artwork/series",
            fileModifiedAtMillis = 2,
            bookCount = 1,
            createdAtMillis = 1,
          ),
        metadata = metadata,
        booksMetadata =
          BookMetadataAggregation(
            createdAtMillis = 1,
            updatedAtMillis = 1,
          ),
        readProgress = null,
      )
    }

    private fun syntheticBook(): CatalogBook {
      val series = syntheticSeries()
      return CatalogBook(
        book =
          Book(
            id = MEDIA_ID,
            libraryId = HIDDEN_LIBRARY_ID,
            seriesId = SERIES_ID,
            name = "Synthetic artwork issue.cbz",
            relativePath = "Synthetic artwork series/Synthetic artwork issue.cbz",
            sourceItemId = "file:///synthetic/artwork/series/issue.cbz",
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
            bookId = MEDIA_ID,
            title = "Synthetic artwork issue",
            number = "1",
            numberSort = 1F,
            createdAtMillis = 1,
          ),
        media = null,
        readProgress = null,
      )
    }

    private fun assertNoArtworkCalls(repository: RecordingArtworkRepository) {
      assertEquals(0, repository.findAllCallCount)
      assertEquals(0, repository.findByIdOrNullCallCount)
      assertEquals(0, repository.findSelectedOrNullCallCount)
      assertEquals(0, repository.contentCallCount)
      assertEquals(0, repository.insertCallCount)
      assertEquals(0, repository.replaceGeneratedCallCount)
      assertEquals(0, repository.replaceSidecarsCallCount)
      assertEquals(0, repository.markSelectedCallCount)
      assertEquals(0, repository.deleteCallCount)
    }
  }
}
