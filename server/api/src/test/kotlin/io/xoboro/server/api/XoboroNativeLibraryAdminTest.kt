package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryAvailabilityLifecycle
import io.xoboro.core.application.LibraryAvailabilityProbe
import io.xoboro.core.application.LibraryEventPublisher
import io.xoboro.core.application.LibraryLifecycle
import io.xoboro.core.application.LibraryMaintenanceQueue
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryRootAccess
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.RootType
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SeriesCover
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeLibraryAdminTest {
  @Test
  fun `forbids non-administrators on all library administration routes without side effects`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      for (route in ADMINISTRATION_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
            bearerAuth(fixture.readerToken)
          }

        assertEquals(HttpStatusCode.Forbidden, response.status, route.path)
        assertEquals(
          "library_administration_forbidden",
          response.body<XoboroApiError>().code,
          route.path,
        )
      }
      fixture.assertNoSideEffects()
      assertEquals(0, fixture.repository.findByIdOrNullCalls)
    }

  @Test
  fun `forbids non-administrator before looking up a nonexistent library`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.post("$LIBRARIES_PATH/missing-library/scan") {
          bearerAuth(fixture.readerToken)
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("library_administration_forbidden", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.repository.findByIdOrNullCalls)
      fixture.assertNoSideEffects()
    }

  @Test
  fun `requires authentication on all library administration routes`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      for (route in ADMINISTRATION_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
          }

        assertEquals(HttpStatusCode.Unauthorized, response.status, route.path)
      }
      fixture.assertNoSideEffects()
      assertEquals(0, fixture.repository.findByIdOrNullCalls)
    }

  @Test
  fun `rejects cross-site cookie mutation before creating a library`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.post(LIBRARIES_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.adminToken)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        response.body<XoboroApiError>().code,
      )
      fixture.assertNoSideEffects()
    }

  @Test
  fun `accepts bearer mutation with a cross-site origin`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.post("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/scan") {
          bearerAuth(fixture.adminToken)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
        }

      assertEquals(HttpStatusCode.Accepted, response.status)
      assertEquals(listOf(LibraryId(AVAILABLE_LIBRARY_ID) to false), fixture.scan.requests)
    }

  @Test
  fun `creates a library and preserves submitted source and settings`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)
      val request = validRequest()

      val response =
        client.post(LIBRARIES_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(request)
        }

      assertEquals(HttpStatusCode.Created, response.status)
      val body = response.body<XoboroLibraryResponse>()
      assertEquals("library-created", body.id)
      assertEquals(request.name, body.name)
      assertEquals(request.source.provider, body.source?.provider)
      assertEquals(request.source.location, body.source?.location)
      assertEquals(false, body.unavailable)
      assertEquals(2_000, body.createdAtMillis)
      assertEquals(2_000, body.updatedAtMillis)
      assertEquals(1, fixture.repository.insertCalls)
      val inserted = assertNotNull(fixture.repository.lastInserted)
      assertEquals(request.name, inserted.name)
      assertEquals(request.source.toSourceLocation(), inserted.root)
      assertEquals(request.settings.toLibrarySettings(), inserted.settings)
      assertEquals(1, fixture.lifecycleMaintenance.scanCalls)
    }

  @Test
  fun `maps library validation failures to stable native errors without creating`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)
      val failures =
        listOf(
          validRequest(sourceLocation = "/synthetic/missing") to
            (HttpStatusCode.BadRequest to "library_root_missing"),
          validRequest(sourceLocation = "/synthetic/file") to
            (HttpStatusCode.BadRequest to "library_root_not_directory"),
          validRequest(
            name = "Available Library",
            sourceLocation = "/synthetic/library/unique",
          ) to (HttpStatusCode.Conflict to "library_name_conflict"),
          validRequest(
            name = "Overlapping Library",
            sourceLocation = "/synthetic/library/available/child",
          ) to (HttpStatusCode.Conflict to "library_root_overlap"),
          validRequest(
            name = " ",
            sourceLocation = "/synthetic/library/blank-name",
          ) to (HttpStatusCode.BadRequest to "invalid_request"),
        )

      for ((request, expected) in failures) {
        val response =
          client.post(LIBRARIES_PATH) {
            bearerAuth(fixture.adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
          }
        assertEquals(expected.first, response.status)
        assertEquals(expected.second, response.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.repository.insertCalls)
      assertEquals(0, fixture.lifecycleMaintenance.scanCalls)
    }

  @Test
  fun `returns not found for update without attempting a repository update`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.put("$LIBRARIES_PATH/missing-library") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(validRequest())
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("library_not_found", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.repository.updateCalls)
    }

  @Test
  fun `updates an existing library and returns the replacement fields`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)
      val request =
        validRequest(
          name = "Updated Library",
          sourceLocation = "/synthetic/library/updated",
        )

      val response =
        client.put("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(request)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroLibraryResponse>()
      assertEquals(AVAILABLE_LIBRARY_ID, body.id)
      assertEquals(request.name, body.name)
      assertEquals(request.source.provider, body.source?.provider)
      assertEquals(request.source.location, body.source?.location)
      assertEquals(1, fixture.repository.updateCalls)
      val updated = assertNotNull(fixture.repository.lastUpdated)
      assertEquals(request.name, updated.name)
      assertEquals(request.source.toSourceLocation(), updated.root)
      assertEquals(request.settings.toLibrarySettings(), updated.settings)
    }

  @Test
  fun `deletes an available library once and then returns not found`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val deleted =
        client.delete("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID") {
          bearerAuth(fixture.adminToken)
        }
      val missing =
        client.delete("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.NoContent, deleted.status)
      assertEquals(HttpStatusCode.NotFound, missing.status)
      assertEquals("library_not_found", missing.body<XoboroApiError>().code)
      assertEquals(1, fixture.repository.deleteCalls)
      assertNull(fixture.libraries.findByIdOrNull(LibraryId(AVAILABLE_LIBRARY_ID)))
    }

  @Test
  fun `refuses to delete an unavailable library and preserves it`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.delete("$LIBRARIES_PATH/$UNAVAILABLE_LIBRARY_ID") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("library_unavailable", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.repository.deleteCalls)
      assertNotNull(fixture.libraries.findByIdOrNull(LibraryId(UNAVAILABLE_LIBRARY_ID)))
    }

  @Test
  fun `clears the unavailable flag when the root is reachable again`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.post("$LIBRARIES_PATH/$UNAVAILABLE_LIBRARY_ID/availability") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val library = response.body<XoboroLibraryResponse>()
      assertFalse(library.unavailable)
      assertNull(library.unavailableSinceMillis)
      // Recovery is persisted, not just reported: the point of the endpoint is that a normal
      // DELETE stops being refused without a full scan having to run first.
      assertNull(
        fixture.repository.findByIdOrNull(LibraryId(UNAVAILABLE_LIBRARY_ID))?.unavailableAtMillis,
      )
    }

  @Test
  fun `marks a library unavailable when its root has gone missing`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.post("$LIBRARIES_PATH/$MISSING_ROOT_LIBRARY_ID/availability") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val library = response.body<XoboroLibraryResponse>()
      assertTrue(library.unavailable)
      assertEquals(PROBE_TIME_MILLIS, library.unavailableSinceMillis)
    }

  @Test
  fun `marks a library unavailable when its root is a directory it cannot read`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      // The discriminating case: the root exists and is a directory, so a check based on root type
      // alone would report available, and the next scan would immediately mark it unavailable
      // again because the inventory requires a *readable* directory.
      val response =
        client.post("$LIBRARIES_PATH/$UNREADABLE_ROOT_LIBRARY_ID/availability") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val library = response.body<XoboroLibraryResponse>()
      assertTrue(library.unavailable)
      assertEquals(PROBE_TIME_MILLIS, library.unavailableSinceMillis)
    }

  @Test
  fun `reports the outage timestamp without probing`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.put("$LIBRARIES_PATH/$UNAVAILABLE_LIBRARY_ID") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(validRequest(name = "Renamed Unavailable Library"))
        }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(1_500, response.body<XoboroLibraryResponse>().unavailableSinceMillis)
    }

  @Test
  fun `returns not found when probing a library that does not exist`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.post("$LIBRARIES_PATH/library-absent/availability") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("library_not_found", response.body<XoboroApiError>().code)
    }

  @Test
  fun `deletes an unavailable library when deletion is forced`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.delete("$LIBRARIES_PATH/$UNAVAILABLE_LIBRARY_ID?force=true") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(1, fixture.repository.deleteCalls)
      assertNull(fixture.libraries.findByIdOrNull(LibraryId(UNAVAILABLE_LIBRARY_ID)))
    }

  @Test
  fun `rejects a non boolean force parameter without deleting`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val response =
        client.delete("$LIBRARIES_PATH/$UNAVAILABLE_LIBRARY_ID?force=maybe") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("invalid_query", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.repository.deleteCalls)
      assertNotNull(fixture.libraries.findByIdOrNull(LibraryId(UNAVAILABLE_LIBRARY_ID)))
    }

  @Test
  fun `enqueues each library task exactly once without cross-wiring requesters`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)
      val id = LibraryId(AVAILABLE_LIBRARY_ID)

      assertEquals(
        HttpStatusCode.Accepted,
        client.post("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/scan") {
          bearerAuth(fixture.adminToken)
        }.status,
      )
      assertEquals(listOf(id to false), fixture.scan.requests)
      assertEquals(0, fixture.maintenance.analyzeCalls)
      assertEquals(0, fixture.maintenance.metadataRefreshCalls)
      assertEquals(0, fixture.maintenance.emptyTrashCalls)

      assertEquals(
        HttpStatusCode.Accepted,
        client.post("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/analyze") {
          bearerAuth(fixture.adminToken)
        }.status,
      )
      assertEquals(1, fixture.scan.requests.size)
      assertEquals(listOf(id), fixture.maintenance.analyses)
      assertEquals(0, fixture.maintenance.metadataRefreshCalls)
      assertEquals(0, fixture.maintenance.emptyTrashCalls)

      assertEquals(
        HttpStatusCode.Accepted,
        client.post("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/metadata-refresh") {
          bearerAuth(fixture.adminToken)
        }.status,
      )
      assertEquals(1, fixture.scan.requests.size)
      assertEquals(1, fixture.maintenance.analyzeCalls)
      assertEquals(listOf(id), fixture.maintenance.metadataRefreshes)
      assertEquals(0, fixture.maintenance.emptyTrashCalls)

      assertEquals(
        HttpStatusCode.Accepted,
        client.post("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/empty-trash") {
          bearerAuth(fixture.adminToken)
        }.status,
      )
      assertEquals(1, fixture.scan.requests.size)
      assertEquals(1, fixture.maintenance.analyzeCalls)
      assertEquals(1, fixture.maintenance.metadataRefreshCalls)
      assertEquals(listOf(id), fixture.maintenance.trashRequests)
    }

  @Test
  fun `returns not found without enqueueing any task for a nonexistent library`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      for (suffix in listOf("scan", "analyze", "metadata-refresh", "empty-trash")) {
        val response =
          client.post("$LIBRARIES_PATH/missing-library/$suffix") {
            bearerAuth(fixture.adminToken)
          }
        assertEquals(HttpStatusCode.NotFound, response.status, suffix)
        assertEquals("library_not_found", response.body<XoboroApiError>().code, suffix)
      }
      assertEquals(0, fixture.scan.requests.size)
      assertEquals(0, fixture.maintenance.analyzeCalls)
      assertEquals(0, fixture.maintenance.metadataRefreshCalls)
      assertEquals(0, fixture.maintenance.emptyTrashCalls)
    }

  @Test
  fun `maps malformed create and update JSON to invalid request without writes`() =
    testApplication {
      val fixture = Fixture()
      installLibraryAdministration(fixture)

      val malformedCreate =
        client.post(LIBRARIES_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody("""{"name":""")
        }
      val incompleteUpdate =
        client.put("$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID") {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody("{}")
        }

      assertEquals(HttpStatusCode.BadRequest, malformedCreate.status)
      assertEquals("invalid_request", malformedCreate.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.BadRequest, incompleteUpdate.status)
      assertEquals("invalid_request", incompleteUpdate.body<XoboroApiError>().code)
      assertEquals(0, fixture.repository.insertCalls)
      assertEquals(0, fixture.repository.updateCalls)
    }

  private fun ApplicationTestBuilder.installLibraryAdministration(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
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
        exception<BadRequestException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed request"),
          )
        }
        exception<ContentTransformationException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed JSON request"),
          )
        }
      }
      routing {
        xoboroNativeLibraryAdminRoutes(
          libraries = fixture.libraries,
          scanRequester = fixture.scan,
          maintenanceRequester = fixture.maintenance,
          availabilityProbe = fixture.availabilityProbe,
        )
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private class Fixture {
    private val users =
      InMemoryUserRepository(
        listOf(
          syntheticUser(
            id = ADMIN_USER_ID,
            email = "admin@example.invalid",
            roles = setOf(UserRole.ADMIN),
          ),
          syntheticUser(
            id = READER_USER_ID,
            email = "reader@example.invalid",
            roles = setOf(UserRole.PAGE_STREAMING),
          ),
        ),
      )
    val repository =
      InMemoryLibraryRepository(
        listOf(
          syntheticLibrary(
            id = AVAILABLE_LIBRARY_ID,
            name = "Available Library",
            root = "/synthetic/library/available",
          ),
          syntheticLibrary(
            id = UNAVAILABLE_LIBRARY_ID,
            name = "Unavailable Library",
            root = "/synthetic/library/unavailable",
            unavailableAtMillis = 1_500,
          ),
          syntheticLibrary(
            id = MISSING_ROOT_LIBRARY_ID,
            name = "Missing Root Library",
            root = "/synthetic/missing",
          ),
          syntheticLibrary(
            id = UNREADABLE_ROOT_LIBRARY_ID,
            name = "Unreadable Root Library",
            root = "/synthetic/unreadable",
          ),
        ),
      )
    val lifecycleMaintenance = RecordingLibraryMaintenanceQueue()
    val libraries =
      LibraryAdministrationLifecycle(
        libraries = repository,
        lifecycle =
          LibraryLifecycle(
            repository = repository,
            rootAccess = SyntheticRootAccess,
            maintenanceQueue = lifecycleMaintenance,
            eventPublisher = LibraryEventPublisher {},
          ),
        libraryIdFactory = { "library-created" },
        currentTimeMillis = { 2_000 },
      )
    val availabilityProbe =
      LibraryAvailabilityProbe(
        libraries = repository,
        rootAccess = SyntheticRootAccess,
        availability =
          LibraryAvailabilityLifecycle(
            libraries = repository,
            currentTimeMillis = { PROBE_TIME_MILLIS },
            eventPublisher = LibraryEventPublisher {},
          ),
      )
    val scan = RecordingLibraryScanRequester()
    val maintenance = RecordingLibraryMaintenanceRequester()
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory =
          listOf("admin-token", "reader-token")
            .iterator()
            .let { tokens -> tokens::next },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val adminToken = sessions.create(requireNotNull(users.findByIdOrNull(ADMIN_USER_ID))).plainToken
    val readerToken = sessions.create(requireNotNull(users.findByIdOrNull(READER_USER_ID))).plainToken

    fun assertNoSideEffects() {
      assertEquals(0, repository.insertCalls)
      assertEquals(0, repository.updateCalls)
      assertEquals(0, repository.deleteCalls)
      assertEquals(0, lifecycleMaintenance.totalCalls)
      assertEquals(0, scan.requests.size)
      assertEquals(0, maintenance.totalCalls)
    }
  }

  private class InMemoryLibraryRepository(
    libraries: Collection<Library>,
  ) : LibraryRepository {
    private val values = libraries.associateByTo(linkedMapOf(), Library::id)
    var findByIdOrNullCalls = 0
      private set
    var insertCalls = 0
      private set
    var updateCalls = 0
      private set
    var deleteCalls = 0
      private set
    var lastInserted: Library? = null
      private set
    var lastUpdated: Library? = null
      private set

    override fun findById(id: LibraryId): Library = requireNotNull(values[id])

    override fun findByIdOrNull(id: LibraryId): Library? {
      findByIdOrNullCalls += 1
      return values[id]
    }

    override fun findAll(): List<Library> = values.values.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> = ids.mapNotNull(values::get)

    override fun insert(library: Library) {
      insertCalls += 1
      lastInserted = library
      values[library.id] = library
    }

    override fun update(library: Library) {
      updateCalls += 1
      lastUpdated = library
      values[library.id] = library
    }

    override fun delete(id: LibraryId) {
      deleteCalls += 1
      values.remove(id)
    }

    override fun deleteAll() {
      values.clear()
    }

    override fun count(): Long = values.size.toLong()
  }

  private class RecordingLibraryMaintenanceQueue : LibraryMaintenanceQueue {
    var scanCalls = 0
      private set
    var rescheduleCalls = 0
      private set
    var hashFileCalls = 0
      private set
    var hashKoreaderCalls = 0
      private set
    var hashPageCalls = 0
      private set
    var repairCalls = 0
      private set
    var convertCalls = 0
      private set
    val totalCalls: Int
      get() =
        scanCalls +
          rescheduleCalls +
          hashFileCalls +
          hashKoreaderCalls +
          hashPageCalls +
          repairCalls +
          convertCalls

    override fun scanLibrary(id: LibraryId) {
      scanCalls += 1
    }

    override fun reschedulePeriodicScan(library: Library) {
      rescheduleCalls += 1
    }

    override fun hashBooksWithoutFileHash(id: LibraryId) {
      hashFileCalls += 1
    }

    override fun hashBooksWithoutKoreaderHash(id: LibraryId) {
      hashKoreaderCalls += 1
    }

    override fun hashBooksWithMissingPageHash(id: LibraryId) {
      hashPageCalls += 1
    }

    override fun repairExtensions(id: LibraryId) {
      repairCalls += 1
    }

    override fun convertBooksToCbz(id: LibraryId) {
      convertCalls += 1
    }
  }

  private class RecordingLibraryScanRequester : LibraryScanRequester {
    val requests = mutableListOf<Pair<LibraryId, Boolean>>()

    override fun request(
      libraryId: LibraryId,
      deep: Boolean,
    ): Boolean {
      requests += libraryId to deep
      return true
    }
  }

  private class RecordingLibraryMaintenanceRequester : LibraryMaintenanceRequester {
    val analyses = mutableListOf<LibraryId>()
    val metadataRefreshes = mutableListOf<LibraryId>()
    val trashRequests = mutableListOf<LibraryId>()
    val analyzeCalls: Int
      get() = analyses.size
    val metadataRefreshCalls: Int
      get() = metadataRefreshes.size
    val emptyTrashCalls: Int
      get() = trashRequests.size
    val totalCalls: Int
      get() = analyzeCalls + metadataRefreshCalls + emptyTrashCalls

    override fun analyze(libraryId: LibraryId): Int {
      analyses += libraryId
      return 1
    }

    override fun refreshMetadata(libraryId: LibraryId): Int {
      metadataRefreshes += libraryId
      return 1
    }

    override fun emptyTrash(libraryId: LibraryId): Boolean {
      trashRequests += libraryId
      return true
    }
  }

  private object SyntheticRootAccess : LibraryRootAccess {
    override fun typeOf(root: SourceLocation): RootType =
      when (root.itemId) {
        "/synthetic/missing" -> RootType.MISSING
        "/synthetic/file" -> RootType.FILE
        else -> RootType.DIRECTORY
      }

    // A directory that exists but cannot be read is the case `typeOf` alone cannot express, and
    // the one where an availability probe would otherwise disagree with the next scan.
    override fun isReadable(root: SourceLocation): Boolean =
      root.itemId != "/synthetic/unreadable"

    override fun isSameOrAncestor(
      possibleAncestor: SourceLocation,
      possibleDescendant: SourceLocation,
    ): Boolean =
      possibleAncestor.sourceId == possibleDescendant.sourceId &&
        (
          possibleDescendant.itemId == possibleAncestor.itemId ||
            possibleDescendant.itemId.startsWith("${possibleAncestor.itemId}/")
        )
  }

  private class InMemoryUserRepository(
    users: Collection<User>,
  ) : UserRepository {
    private val values = users.associateByTo(linkedMapOf(), User::id)

    override fun count(): Long = values.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = values[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      values.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = values.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      values[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (values.isNotEmpty()) return false
      values[user.id] = user
      return true
    }

    override fun update(user: User) {
      values[user.id] = user
    }

    override fun delete(id: UserId) {
      values.remove(id)
    }
  }

  private data class AdministrationRoute(
    val method: HttpMethod,
    val path: String,
  )

  companion object {
    private const val LIBRARIES_PATH = "$XOBORO_API_PREFIX/libraries"
    private const val AVAILABLE_LIBRARY_ID = "library-available"
    private const val UNAVAILABLE_LIBRARY_ID = "library-unavailable"
    private const val MISSING_ROOT_LIBRARY_ID = "library-missing-root"
    private const val UNREADABLE_ROOT_LIBRARY_ID = "library-unreadable-root"
    private const val PROBE_TIME_MILLIS = 3_000L
    private val ADMIN_USER_ID = UserId("admin-user")
    private val READER_USER_ID = UserId("reader-user")
    private val ADMINISTRATION_ROUTES =
      listOf(
        AdministrationRoute(HttpMethod.Post, LIBRARIES_PATH),
        AdministrationRoute(HttpMethod.Put, "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID"),
        AdministrationRoute(HttpMethod.Delete, "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID"),
        AdministrationRoute(HttpMethod.Post, "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/scan"),
        AdministrationRoute(HttpMethod.Post, "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/analyze"),
        AdministrationRoute(
          HttpMethod.Post,
          "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/metadata-refresh",
        ),
        AdministrationRoute(HttpMethod.Post, "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/empty-trash"),
        AdministrationRoute(HttpMethod.Post, "$LIBRARIES_PATH/$AVAILABLE_LIBRARY_ID/availability"),
      )

    private fun validRequest(
      name: String = "Synthetic Library",
      sourceLocation: String = "/synthetic/library/created",
    ): XoboroLibraryAdministrationRequest =
      XoboroLibraryAdministrationRequest(
        name = name,
        source =
          XoboroLibrarySourceRequest(
            provider = "synthetic",
            location = sourceLocation,
          ),
        settings =
          XoboroLibrarySettingsRequest(
            importComicInfoCollection = false,
            scanOnStartup = true,
            scanInterval = ScanInterval.DAILY.name,
            scanCbx = false,
            scanDirectoryExclusions = setOf("cache", "temporary"),
            seriesCover = SeriesCover.LAST.name,
            hashPages = true,
            oneshotsDirectory = "single-issues",
          ),
      )

    private fun syntheticLibrary(
      id: String,
      name: String,
      root: String,
      unavailableAtMillis: Long? = null,
    ): Library =
      Library(
        id = LibraryId(id),
        name = name,
        root = SourceLocation("synthetic", root),
        settings = LibrarySettings(),
        unavailableAtMillis = unavailableAtMillis,
        createdAtMillis = 1_000,
      )

    private fun syntheticUser(
      id: UserId,
      email: String,
      roles: Set<UserRole>,
    ): User =
      User(
        id = id,
        email = email,
        passwordHash = "synthetic-hash",
        roles = roles,
        sharesAllLibraries = true,
        createdAtMillis = 1,
      )
  }
}
