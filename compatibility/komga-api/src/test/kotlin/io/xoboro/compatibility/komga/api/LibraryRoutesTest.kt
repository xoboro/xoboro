package io.xoboro.compatibility.komga.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryEvent
import io.xoboro.core.application.LibraryLifecycle
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryMaintenanceQueue
import io.xoboro.core.application.LibraryRootAccess
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.application.RootType
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class LibraryRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `matches Komga library CRUD visibility and patch semantics`() {
    withLibraryApi("library-crud.sqlite") {
        client,
        users,
        libraries,
        maintenance,
        _,
        _,
        _,
      ->
      client.claimAdministrator()
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("/api/v1/libraries").status,
      )

      val firstRoot = tempDirectory.resolve("synthetic-library-a").createDirectories()
      val createdResponse =
        client.post("/api/v1/libraries") {
          adminCredentials()
          jsonBody(
            LibraryCreationDto(
              name = "Synthetic Alpha",
              root = firstRoot.toString(),
              scanInterval = ScanIntervalDto.DAILY,
              scanOnStartup = true,
              scanDirectoryExclusions = setOf("@eaDir"),
              oneshotsDirectory = "Singles",
            ),
          )
        }
      assertEquals(HttpStatusCode.OK, createdResponse.status)
      val created = createdResponse.body<LibraryDto>()
      assertEquals(firstRoot.toString(), created.root)
      assertEquals(ScanIntervalDto.DAILY, created.scanInterval)
      assertEquals(setOf("@eaDir"), created.scanDirectoryExclusions)
      assertEquals(listOf(LibraryId(created.id)), maintenance.scans)

      users.createUser(
        email = READER_EMAIL,
        rawPassword = READER_PASSWORD,
        sharedLibraryIds = setOf(LibraryId(created.id)),
        sharesAllLibraries = false,
      )
      val readerLibraries =
        client.get("/api/v1/libraries") {
          readerCredentials()
        }.body<List<LibraryDto>>()
      assertEquals(1, readerLibraries.size)
      assertEquals("", readerLibraries.single().root)

      val secondRoot = tempDirectory.resolve("synthetic-library-b").createDirectories()
      val second =
        client.post("/api/v1/libraries") {
          adminCredentials()
          jsonBody(LibraryCreationDto("Synthetic Beta", secondRoot.toString()))
        }.body<LibraryDto>()
      assertEquals(
        listOf("Synthetic Alpha"),
        client.get("/api/v1/libraries") {
          readerCredentials()
        }.body<List<LibraryDto>>().map(LibraryDto::name),
      )
      assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/libraries/${second.id}") {
          readerCredentials()
        }.status,
      )

      val patch =
        buildJsonObject {
          put("name", "Synthetic Renamed")
          put("scanInterval", ScanIntervalDto.HOURLY.name)
          put("scanCbx", false)
          put("hashPages", true)
          put("seriesCover", SeriesCoverDto.LAST.name)
          put("scanDirectoryExclusions", JsonNull)
          put("oneshotsDirectory", JsonNull)
        }
      assertEquals(
        HttpStatusCode.NoContent,
        client.patch("/api/v1/libraries/${created.id}") {
          adminCredentials()
          jsonBody(patch)
        }.status,
      )
      val updated =
        client.get("/api/v1/libraries/${created.id}") {
          adminCredentials()
        }.body<LibraryDto>()
      assertEquals("Synthetic Renamed", updated.name)
      assertEquals(ScanIntervalDto.HOURLY, updated.scanInterval)
      assertFalse(updated.scanCbx)
      assertTrue(updated.hashPages)
      assertEquals(SeriesCoverDto.LAST, updated.seriesCover)
      assertTrue(updated.scanDirectoryExclusions.isEmpty())
      assertNull(updated.oneshotsDirectory)
      assertEquals(listOf(LibraryId(created.id)), maintenance.rescheduled)

      assertEquals(
        HttpStatusCode.NoContent,
        client.put("/api/v1/libraries/${created.id}") {
          adminCredentials()
          jsonBody(buildJsonObject { put("scanPdf", false) })
        }.status,
      )
      assertFalse(
        client.get("/api/v1/libraries/${created.id}") {
          adminCredentials()
        }.body<LibraryDto>().scanPdf,
      )

      assertEquals(
        HttpStatusCode.Forbidden,
        client.delete("/api/v1/libraries/${created.id}") {
          readerCredentials()
        }.status,
      )
      assertEquals(
        HttpStatusCode.NoContent,
        client.delete("/api/v1/libraries/${created.id}") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client.get("/api/v1/libraries/${created.id}") {
          adminCredentials()
        }.status,
      )
    }
  }

  @Test
  fun `validates local roots and queues highest-level manual scan requests`() {
    withLibraryApi("library-validation.sqlite") {
        client,
        _,
        _,
        _,
        scans,
        analyses,
        trash,
      ->
      client.claimAdministrator()
      val root = tempDirectory.resolve("validated-root").createDirectories()
      val otherRoot = tempDirectory.resolve("other").createDirectories()
      val created =
        client.post("/api/v1/libraries") {
          adminCredentials()
          jsonBody(LibraryCreationDto("Synthetic Root", root.toString()))
        }.body<LibraryDto>()

      listOf(
        LibraryCreationDto("Synthetic Root", otherRoot.toString()),
        LibraryCreationDto(
          "Synthetic Nested",
          root.resolve("nested").createDirectories().toString(),
        ),
        LibraryCreationDto(
          "Synthetic Missing",
          tempDirectory.resolve("missing-root").toString(),
        ),
      ).forEach { invalid ->
        assertEquals(
          HttpStatusCode.BadRequest,
          client.post("/api/v1/libraries") {
            adminCredentials()
            jsonBody(invalid)
          }.status,
        )
      }

      assertEquals(
        HttpStatusCode.Accepted,
        client.post("/api/v1/libraries/${created.id}/scan?deep=true") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        HttpStatusCode.Accepted,
        client.post("/api/v1/libraries/${created.id}/scan") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        listOf(
          LibraryId(created.id) to true,
          LibraryId(created.id) to false,
        ),
        scans,
      )
      assertEquals(
        HttpStatusCode.BadRequest,
        client.post("/api/v1/libraries/${created.id}/scan?deep=invalid") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client.post("/api/v1/libraries/missing-library/scan") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        HttpStatusCode.Accepted,
        client.post("/api/v1/libraries/${created.id}/analyze") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        HttpStatusCode.Accepted,
        client.post("/api/v1/libraries/missing-library/analyze") {
          adminCredentials()
        }.status,
      )
      assertEquals(
        listOf(LibraryId(created.id), LibraryId("missing-library")),
        analyses,
      )
      assertEquals(
        HttpStatusCode.Accepted,
        client.post("/api/v1/libraries/${created.id}/empty-trash") {
          adminCredentials()
        }.status,
      )
      assertEquals(listOf(LibraryId(created.id)), trash)
      assertEquals(
        HttpStatusCode.NotFound,
        client.post("/api/v1/libraries/missing-library/empty-trash") {
          adminCredentials()
        }.status,
      )
    }
  }

  private fun withLibraryApi(
    databaseName: String,
    assertions:
      suspend ApplicationTestBuilder.(
        client: HttpClient,
        users: UserLifecycle,
        libraries: LibraryAdministrationLifecycle,
        maintenance: RecordingMaintenanceQueue,
        scans: MutableList<Pair<LibraryId, Boolean>>,
        analyses: MutableList<LibraryId>,
        trash: MutableList<LibraryId>,
      ) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve(databaseName))).use { database ->
      val userIds = listOf("admin-1", "reader-1").iterator()
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = userIds::next,
          currentTimeMillis = { 1_000 },
        )
      val repository = JooqLibraryRepository(database)
      val maintenance = RecordingMaintenanceQueue()
      val events = mutableListOf<LibraryEvent>()
      var nextLibraryId = 0
      val libraries =
        LibraryAdministrationLifecycle(
          libraries = repository,
          lifecycle =
            LibraryLifecycle(
              repository = repository,
              rootAccess = LocalTestRootAccess,
              maintenanceQueue = maintenance,
              eventPublisher = events::add,
            ),
          libraryIdFactory = {
            nextLibraryId += 1
            "library-$nextLibraryId"
          },
          currentTimeMillis = { 2_000L + nextLibraryId },
        )
      val scans = mutableListOf<Pair<LibraryId, Boolean>>()
      val analyses = mutableListOf<LibraryId>()
      val trash = mutableListOf<LibraryId>()
      testApplication {
        application {
          install(ServerContentNegotiation) {
            json(KOMGA_JSON)
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaLibraryRoutes(
              libraries = libraries,
              scanRequester =
                LibraryScanRequester { id, deep ->
                  scans += id to deep
                  true
                },
              maintenanceRequester =
                object : LibraryMaintenanceRequester {
                  override fun analyze(libraryId: LibraryId): Int {
                    analyses += libraryId
                    return 1
                  }

                  override fun emptyTrash(libraryId: LibraryId): Boolean {
                    trash += libraryId
                    return true
                  }
                },
            )
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json(KOMGA_JSON)
            }
          }
        assertions(client, users, libraries, maintenance, scans, analyses, trash)
      }
    }
  }

  private suspend fun HttpClient.claimAdministrator() {
    assertEquals(
      HttpStatusCode.OK,
      post("/api/v1/claim") {
        header("X-Komga-Email", ADMIN_EMAIL)
        header("X-Komga-Password", ADMIN_PASSWORD)
      }.status,
    )
  }

  private fun HttpRequestBuilder.adminCredentials() {
    basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
  }

  private fun HttpRequestBuilder.readerCredentials() {
    basicAuth(READER_EMAIL, READER_PASSWORD)
  }

  private fun HttpRequestBuilder.jsonBody(value: LibraryCreationDto) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(value)
  }

  private fun HttpRequestBuilder.jsonBody(value: JsonObject) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(value.toString())
  }

  private object LocalTestRootAccess : LibraryRootAccess {
    override fun typeOf(root: SourceLocation): RootType {
      val path = root.toPath()
      return when {
        !Files.exists(path) -> RootType.MISSING
        Files.isDirectory(path) -> RootType.DIRECTORY
        else -> RootType.FILE
      }
    }

    override fun isSameOrAncestor(
      possibleAncestor: SourceLocation,
      possibleDescendant: SourceLocation,
    ): Boolean =
      possibleDescendant.toPath().startsWith(possibleAncestor.toPath())

    private fun SourceLocation.toPath(): Path =
      Path.of(URI(itemId)).toAbsolutePath().normalize()
  }

  private class RecordingMaintenanceQueue : LibraryMaintenanceQueue {
    val scans = mutableListOf<LibraryId>()
    val rescheduled = mutableListOf<LibraryId>()

    override fun scanLibrary(id: LibraryId) {
      scans += id
    }

    override fun reschedulePeriodicScan(library: Library) {
      rescheduled += library.id
    }

    override fun hashBooksWithoutFileHash(id: LibraryId) = Unit

    override fun hashBooksWithoutKoreaderHash(id: LibraryId) = Unit

    override fun hashBooksWithMissingPageHash(id: LibraryId) = Unit

    override fun repairExtensions(id: LibraryId) = Unit

    override fun convertBooksToCbz(id: LibraryId) = Unit
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val READER_EMAIL = "reader@example.invalid"
    const val READER_PASSWORD = "synthetic-reader-password"
    val KOMGA_JSON = Json { explicitNulls = false }
  }
}
