package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.LibraryAdministrationLifecycle
import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.LibraryScanRequester
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SeriesCover
import io.xoboro.core.domain.SourceLocation
import java.net.URI
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

fun Route.komgaLibraryRoutes(
  libraries: LibraryAdministrationLifecycle,
  scanRequester: LibraryScanRequester,
  maintenanceRequester: LibraryMaintenanceRequester,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/libraries") {
      get {
        val principal = call.komgaLibraryPrincipal()
        val visible =
          if (principal.user.canAccessAllLibraries()) {
            libraries.findAll()
          } else {
            libraries.findAll().filter { principal.user.canAccessLibrary(it.id) }
          }
        call.respond(
          LIBRARY_RESPONSE_JSON.encodeToJsonElement(
            visible
              .sortedBy { it.name.lowercase() }
              .map { it.toDto(includeRoot = principal.user.isAdmin) },
          ),
        )
      }
      get("/{libraryId}") {
        val principal = call.komgaLibraryPrincipal()
        val library = libraries.findByIdOrNull(call.libraryId())
        if (library == null) {
          call.respondLibraryNotFound()
          return@get
        }
        if (!principal.user.canAccessLibrary(library.id)) {
          call.respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
          return@get
        }
        call.respond(
          LIBRARY_RESPONSE_JSON.encodeToJsonElement(
            library.toDto(includeRoot = principal.user.isAdmin),
          ),
        )
      }
      post {
        if (!call.requireLibraryAdministrator()) return@post
        val request = call.receive<LibraryCreationDto>()
        if (request.name.isBlank() || request.root.isBlank()) {
          call.respondLibraryValidation("name and root must not be blank")
          return@post
        }
        try {
          val created =
            libraries.create(
              name = request.name,
              root = request.root.toLocalSourceLocation(),
              settings = request.toSettings(),
            )
          call.respond(
            LIBRARY_RESPONSE_JSON.encodeToJsonElement(
              created.toDto(includeRoot = true),
            ),
          )
        } catch (failure: IllegalArgumentException) {
          call.respondLibraryValidation(failure.message ?: "Invalid library")
        }
      }
      route("/{libraryId}") {
        patch {
          call.updateLibrary(libraries)
        }
        put {
          call.updateLibrary(libraries)
        }
        delete {
          if (!call.requireLibraryAdministrator()) return@delete
          if (!libraries.delete(call.libraryId())) {
            call.respondLibraryNotFound()
            return@delete
          }
          call.respond(HttpStatusCode.NoContent)
        }
        post("/scan") {
          if (!call.requireLibraryAdministrator()) return@post
          val id = call.libraryId()
          if (libraries.findByIdOrNull(id) == null) {
            call.respondLibraryNotFound()
            return@post
          }
          val deep =
            call.request.queryParameters["deep"]
              ?.toBooleanStrictOrNull()
              ?: if ("deep" in call.request.queryParameters) {
                call.respondLibraryValidation("deep must be a boolean")
                return@post
              } else {
                false
              }
          scanRequester.request(id, deep)
          call.respond(HttpStatusCode.Accepted)
        }
        post("/analyze") {
          if (!call.requireLibraryAdministrator()) return@post
          maintenanceRequester.analyze(call.libraryId())
          call.respond(HttpStatusCode.Accepted)
        }
        post("/metadata/refresh") {
          if (!call.requireLibraryAdministrator()) return@post
          maintenanceRequester.refreshMetadata(call.libraryId())
          call.respond(HttpStatusCode.Accepted)
        }
        post("/empty-trash") {
          if (!call.requireLibraryAdministrator()) return@post
          val id = call.libraryId()
          if (libraries.findByIdOrNull(id) == null) {
            call.respondLibraryNotFound()
            return@post
          }
          maintenanceRequester.emptyTrash(id)
          call.respond(HttpStatusCode.Accepted)
        }
      }
    }
  }
}

@Serializable
data class LibraryDto(
  val id: String,
  val name: String,
  val root: String,
  val importComicInfoBook: Boolean,
  val importComicInfoSeries: Boolean,
  val importComicInfoCollection: Boolean,
  val importComicInfoReadList: Boolean,
  val importComicInfoSeriesAppendVolume: Boolean,
  val importEpubBook: Boolean,
  val importEpubSeries: Boolean,
  val importMylarSeries: Boolean,
  val importLocalArtwork: Boolean,
  val importBarcodeIsbn: Boolean,
  val scanForceModifiedTime: Boolean,
  val scanInterval: ScanIntervalDto,
  val scanOnStartup: Boolean,
  val scanCbx: Boolean,
  val scanPdf: Boolean,
  val scanEpub: Boolean,
  val scanDirectoryExclusions: Set<String>,
  val repairExtensions: Boolean,
  val convertToCbz: Boolean,
  val emptyTrashAfterScan: Boolean,
  val seriesCover: SeriesCoverDto,
  val hashFiles: Boolean,
  val hashPages: Boolean,
  val hashKoreader: Boolean,
  val analyzeDimensions: Boolean,
  val oneshotsDirectory: String? = null,
  val unavailable: Boolean,
)

@Serializable
data class LibraryCreationDto(
  val name: String,
  val root: String,
  val importComicInfoBook: Boolean = true,
  val importComicInfoSeries: Boolean = true,
  val importComicInfoCollection: Boolean = true,
  val importComicInfoReadList: Boolean = true,
  val importComicInfoSeriesAppendVolume: Boolean = true,
  val importEpubBook: Boolean = true,
  val importEpubSeries: Boolean = true,
  val importMylarSeries: Boolean = true,
  val importLocalArtwork: Boolean = true,
  val importBarcodeIsbn: Boolean = true,
  val scanForceModifiedTime: Boolean = false,
  val scanInterval: ScanIntervalDto = ScanIntervalDto.EVERY_6H,
  val scanOnStartup: Boolean = false,
  val scanCbx: Boolean = true,
  val scanPdf: Boolean = true,
  val scanEpub: Boolean = true,
  val scanDirectoryExclusions: Set<String> = emptySet(),
  val repairExtensions: Boolean = false,
  val convertToCbz: Boolean = false,
  val emptyTrashAfterScan: Boolean = false,
  val seriesCover: SeriesCoverDto = SeriesCoverDto.FIRST,
  val hashFiles: Boolean = true,
  val hashPages: Boolean = false,
  val hashKoreader: Boolean = false,
  val analyzeDimensions: Boolean = true,
  val oneshotsDirectory: String? = null,
)

@Serializable
enum class ScanIntervalDto {
  DISABLED,
  HOURLY,
  EVERY_6H,
  EVERY_12H,
  DAILY,
  WEEKLY,
}

@Serializable
enum class SeriesCoverDto {
  FIRST,
  FIRST_UNREAD_OR_FIRST,
  FIRST_UNREAD_OR_LAST,
  LAST,
}

private suspend fun ApplicationCall.updateLibrary(libraries: LibraryAdministrationLifecycle) {
  if (!requireLibraryAdministrator()) return
  val id = libraryId()
  if (libraries.findByIdOrNull(id) == null) {
    respondLibraryNotFound()
    return
  }
  val patch = receive<JsonObject>()
  try {
    libraries.update(id) { existing ->
      existing.applyPatch(patch)
    }
    respond(HttpStatusCode.NoContent)
  } catch (failure: IllegalArgumentException) {
    respondLibraryValidation(failure.message ?: "Invalid library update")
  }
}

private fun Library.applyPatch(patch: JsonObject): Library {
  val updatedSettings =
    settings.copy(
      importComicInfoBook = patch.booleanOr("importComicInfoBook", settings.importComicInfoBook),
      importComicInfoSeries =
        patch.booleanOr("importComicInfoSeries", settings.importComicInfoSeries),
      importComicInfoCollection =
        patch.booleanOr("importComicInfoCollection", settings.importComicInfoCollection),
      importComicInfoReadList =
        patch.booleanOr("importComicInfoReadList", settings.importComicInfoReadList),
      importComicInfoSeriesAppendVolume =
        patch.booleanOr(
          "importComicInfoSeriesAppendVolume",
          settings.importComicInfoSeriesAppendVolume,
        ),
      importEpubBook = patch.booleanOr("importEpubBook", settings.importEpubBook),
      importEpubSeries = patch.booleanOr("importEpubSeries", settings.importEpubSeries),
      importMylarSeries = patch.booleanOr("importMylarSeries", settings.importMylarSeries),
      importLocalArtwork = patch.booleanOr("importLocalArtwork", settings.importLocalArtwork),
      importBarcodeIsbn = patch.booleanOr("importBarcodeIsbn", settings.importBarcodeIsbn),
      scanForceModifiedTime =
        patch.booleanOr("scanForceModifiedTime", settings.scanForceModifiedTime),
      scanInterval = patch.enumOr<ScanIntervalDto>("scanInterval", settings.scanInterval.name).toDomain(),
      scanOnStartup = patch.booleanOr("scanOnStartup", settings.scanOnStartup),
      scanCbx = patch.booleanOr("scanCbx", settings.scanCbx),
      scanPdf = patch.booleanOr("scanPdf", settings.scanPdf),
      scanEpub = patch.booleanOr("scanEpub", settings.scanEpub),
      scanDirectoryExclusions =
        if ("scanDirectoryExclusions" in patch) {
          patch["scanDirectoryExclusions"]
            ?.takeUnless { it is JsonNull }
            ?.let { LIBRARY_WIRE_JSON.decodeFromJsonElement<Set<String>>(it) }
            .orEmpty()
        } else {
          settings.scanDirectoryExclusions
        },
      repairExtensions = patch.booleanOr("repairExtensions", settings.repairExtensions),
      convertToCbz = patch.booleanOr("convertToCbz", settings.convertToCbz),
      emptyTrashAfterScan =
        patch.booleanOr("emptyTrashAfterScan", settings.emptyTrashAfterScan),
      seriesCover = patch.enumOr<SeriesCoverDto>("seriesCover", settings.seriesCover.name).toDomain(),
      hashFiles = patch.booleanOr("hashFiles", settings.hashFiles),
      hashPages = patch.booleanOr("hashPages", settings.hashPages),
      hashKoreader = patch.booleanOr("hashKoreader", settings.hashKoreader),
      analyzeDimensions = patch.booleanOr("analyzeDimensions", settings.analyzeDimensions),
      oneshotsDirectory =
        if ("oneshotsDirectory" in patch) {
          patch.nullableString("oneshotsDirectory")?.ifBlank { null }
        } else {
          settings.oneshotsDirectory
        },
    )
  return copy(
    name = patch.nullableString("name") ?: name,
    root = patch.nullableString("root")?.toLocalSourceLocation() ?: root,
    settings = updatedSettings,
  )
}

private fun LibraryCreationDto.toSettings(): LibrarySettings =
  LibrarySettings(
    importComicInfoBook = importComicInfoBook,
    importComicInfoSeries = importComicInfoSeries,
    importComicInfoCollection = importComicInfoCollection,
    importComicInfoReadList = importComicInfoReadList,
    importComicInfoSeriesAppendVolume = importComicInfoSeriesAppendVolume,
    importEpubBook = importEpubBook,
    importEpubSeries = importEpubSeries,
    importMylarSeries = importMylarSeries,
    importLocalArtwork = importLocalArtwork,
    importBarcodeIsbn = importBarcodeIsbn,
    scanForceModifiedTime = scanForceModifiedTime,
    scanOnStartup = scanOnStartup,
    scanInterval = scanInterval.toDomain(),
    scanCbx = scanCbx,
    scanPdf = scanPdf,
    scanEpub = scanEpub,
    scanDirectoryExclusions = scanDirectoryExclusions,
    repairExtensions = repairExtensions,
    convertToCbz = convertToCbz,
    emptyTrashAfterScan = emptyTrashAfterScan,
    seriesCover = seriesCover.toDomain(),
    hashFiles = hashFiles,
    hashPages = hashPages,
    hashKoreader = hashKoreader,
    analyzeDimensions = analyzeDimensions,
    oneshotsDirectory = oneshotsDirectory?.ifBlank { null },
  )

private fun Library.toDto(includeRoot: Boolean): LibraryDto =
  LibraryDto(
    id = id.value,
    name = name,
    root = if (includeRoot) root.toKomgaRoot() else "",
    importComicInfoBook = settings.importComicInfoBook,
    importComicInfoSeries = settings.importComicInfoSeries,
    importComicInfoCollection = settings.importComicInfoCollection,
    importComicInfoReadList = settings.importComicInfoReadList,
    importComicInfoSeriesAppendVolume = settings.importComicInfoSeriesAppendVolume,
    importEpubBook = settings.importEpubBook,
    importEpubSeries = settings.importEpubSeries,
    importMylarSeries = settings.importMylarSeries,
    importLocalArtwork = settings.importLocalArtwork,
    importBarcodeIsbn = settings.importBarcodeIsbn,
    scanForceModifiedTime = settings.scanForceModifiedTime,
    scanInterval = ScanIntervalDto.valueOf(settings.scanInterval.name),
    scanOnStartup = settings.scanOnStartup,
    scanCbx = settings.scanCbx,
    scanPdf = settings.scanPdf,
    scanEpub = settings.scanEpub,
    scanDirectoryExclusions = settings.scanDirectoryExclusions,
    repairExtensions = settings.repairExtensions,
    convertToCbz = settings.convertToCbz,
    emptyTrashAfterScan = settings.emptyTrashAfterScan,
    seriesCover = SeriesCoverDto.valueOf(settings.seriesCover.name),
    hashFiles = settings.hashFiles,
    hashPages = settings.hashPages,
    hashKoreader = settings.hashKoreader,
    analyzeDimensions = settings.analyzeDimensions,
    oneshotsDirectory = settings.oneshotsDirectory,
    unavailable = unavailableAtMillis != null,
  )

private fun String.toLocalSourceLocation(): SourceLocation {
  require(isNotBlank()) { "Library root must not be blank" }
  val path = Path.of(this).toAbsolutePath().normalize()
  return SourceLocation(sourceId = LOCAL_SOURCE_ID, itemId = path.toUri().toString())
}

/**
 * Renders a library root for Komga's `root` field, which is a display string rather than something
 * a client resolves.
 *
 * A non-local source used to `require` its way to a `500` here, which took the whole listing down:
 * `GET /api/v1/libraries` answered `500 IllegalArgumentException` for *every* library as soon as one
 * WebDAV library existed, so a Komga client could not enumerate libraries at all. Creating a library
 * through this surface still refuses anything but a local path (see [toLocalSourceLocation]) - that
 * restriction is real, and reporting an existing library is not the place to enforce it.
 *
 * The remote form drops any URL fragment, because the fragment carries an operator-chosen credential
 * id (see `WebDavCredentialsResolver`) and a credential's name is not something a compatibility
 * surface should hand out. The field is admin-only either way.
 */
private fun SourceLocation.toKomgaRoot(): String =
  if (sourceId == LOCAL_SOURCE_ID) {
    Path.of(URI(itemId)).toString()
  } else {
    itemId.substringBefore('#')
  }

private fun JsonObject.booleanOr(
  name: String,
  fallback: Boolean,
): Boolean =
  get(name)
    ?.takeUnless { it is JsonNull }
    ?.jsonPrimitive
    ?.boolean
    ?: fallback

private inline fun <reified T : Enum<T>> JsonObject.enumOr(
  name: String,
  fallback: String,
): T =
  get(name)
    ?.takeUnless { it is JsonNull }
    ?.jsonPrimitive
    ?.content
    ?.let { enumValueOf<T>(it) }
    ?: enumValueOf(fallback)

private fun JsonObject.nullableString(name: String): String? =
  get(name)
    ?.takeUnless { it is JsonNull }
    ?.jsonPrimitive
    ?.content

private fun ScanIntervalDto.toDomain(): ScanInterval = ScanInterval.valueOf(name)

private fun SeriesCoverDto.toDomain(): SeriesCover = SeriesCover.valueOf(name)

private fun ApplicationCall.komgaLibraryPrincipal(): KomgaPrincipal =
  requireNotNull(principal<KomgaPrincipal>())

private fun ApplicationCall.libraryId(): LibraryId =
  LibraryId(requireNotNull(parameters["libraryId"]))

private suspend fun ApplicationCall.requireLibraryAdministrator(): Boolean {
  if (komgaLibraryPrincipal().user.isAdmin) return true
  respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
  return false
}

private suspend fun ApplicationCall.respondLibraryNotFound() {
  respondError(HttpStatusCode.NotFound, HttpStatusCode.NotFound.description)
}

private suspend fun ApplicationCall.respondLibraryValidation(message: String) {
  respondError(HttpStatusCode.BadRequest, message)
}

private val LIBRARY_WIRE_JSON = Json { ignoreUnknownKeys = true }
private val LIBRARY_RESPONSE_JSON = Json { explicitNulls = true }
private const val LOCAL_SOURCE_ID: String = "local"
