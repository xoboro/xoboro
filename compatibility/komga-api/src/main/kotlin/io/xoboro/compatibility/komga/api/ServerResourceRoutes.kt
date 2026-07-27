package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CompatibilityMaintenanceRequester
import io.xoboro.core.application.FontResourceCatalog
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.ServerRelease
import io.xoboro.core.application.ServerReleaseCatalog
import io.xoboro.core.domain.PageHashMatch
import kotlinx.serialization.Serializable

fun Route.komgaServerResourceRoutes(
  fonts: FontResourceCatalog,
  releases: ServerReleaseCatalog,
  maintenance: CompatibilityMaintenanceRequester,
  pageHashes: PageHashRepository,
  applicationVersion: String,
) {
  get("/api/v1/fonts/resource/{fontFamily}/css") {
    val family = requireNotNull(call.parameters["fontFamily"])
    val css = fonts.css(family)
    if (css == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(
          ContentDisposition.Parameters.FileName,
          "$family.css",
        ).toString(),
      )
      call.respondText(css, ContentType.Text.CSS)
    }
  }
  get("/api/v1/fonts/resource/{fontFamily}/{fontFile}") {
    val resource =
      fonts.resource(
        family = requireNotNull(call.parameters["fontFamily"]),
        fileName = requireNotNull(call.parameters["fontFile"]),
      )
    if (resource == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(
          ContentDisposition.Parameters.FileName,
          resource.fileName,
        ).toString(),
      )
      call.respondBytes(resource.bytes, ContentType.parse(resource.mediaType))
    }
  }
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/fonts/families") {
      call.respond(fonts.families())
    }
    get("/actuator/info") {
      if (!call.requireResourceAdministrator()) return@get
      call.respond(serverInformation(applicationVersion))
    }
    get("/api/v1/releases") {
      if (!call.requireResourceAdministrator()) return@get
      call.respond(releases.releases().map(ServerRelease::toDto))
    }
    put("/api/v1/books/thumbnails") {
      if (!call.requireResourceAdministrator()) return@put
      val rawBiggerOnly = call.request.queryParameters["for_bigger_result_only"]
      val biggerOnly = rawBiggerOnly?.toBooleanStrictOrNull() ?: false
      if (rawBiggerOnly != null && rawBiggerOnly.toBooleanStrictOrNull() == null) {
        call.respond(HttpStatusCode.BadRequest)
        return@put
      }
      maintenance.regenerateBookArtwork(biggerOnly)
      call.respond(HttpStatusCode.Accepted)
    }
    post("/api/v1/page-hashes/{pageHash}/delete-all") {
      if (!call.requireResourceAdministrator()) return@post
      val matches =
        pageHashes.findMatches(
          hash = requireNotNull(call.parameters["pageHash"]),
          page = CatalogPageRequest(unpaged = true),
        ).content
      maintenance.deleteDuplicatePages(
        hash = requireNotNull(call.parameters["pageHash"]),
        matches = matches,
      )
      call.respond(HttpStatusCode.Accepted)
    }
    post("/api/v1/page-hashes/{pageHash}/delete-match") {
      if (!call.requireResourceAdministrator()) return@post
      val hash = requireNotNull(call.parameters["pageHash"])
      val requested = call.receive<PageHashMatchDto>()
      val match =
        pageHashes
          .findMatches(hash, CatalogPageRequest(unpaged = true))
          .content
          .firstOrNull { it.matches(requested) }
      if (match == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        maintenance.deleteDuplicatePages(hash, listOf(match))
        call.respond(HttpStatusCode.Accepted)
      }
    }
  }
}

@Serializable
data class ReleaseDto(
  val version: String,
  val releaseDate: String,
  val url: String,
  val latest: Boolean,
  val preRelease: Boolean,
  val description: String,
)

@Serializable
data class ServerInformationDto(
  val build: BuildInformationDto,
  val java: JavaInformationDto,
  val os: OperatingSystemInformationDto,
)

@Serializable
data class BuildInformationDto(
  val artifact: String,
  val name: String,
  val version: String,
  val group: String,
)

@Serializable
data class JavaInformationDto(
  val version: String,
  val vendor: RuntimeComponentDto,
  val runtime: RuntimeComponentDto,
  val jvm: RuntimeComponentDto,
)

@Serializable
data class RuntimeComponentDto(
  val name: String,
  val version: String,
  val vendor: String? = null,
)

@Serializable
data class OperatingSystemInformationDto(
  val name: String,
  val version: String,
  val arch: String,
)

private fun ServerRelease.toDto(): ReleaseDto =
  ReleaseDto(version, releaseDate, url, latest, preRelease, description)

private fun serverInformation(applicationVersion: String): ServerInformationDto =
  ServerInformationDto(
    build =
      BuildInformationDto(
        artifact = "xoboro-server",
        name = "xoboro-server",
        version = applicationVersion,
        group = "io.xoboro",
      ),
    java =
      JavaInformationDto(
        version = property("java.version"),
        vendor =
          RuntimeComponentDto(
            name = property("java.vendor"),
            version = property("java.vendor.version"),
          ),
        runtime =
          RuntimeComponentDto(
            name = property("java.runtime.name"),
            version = property("java.runtime.version"),
          ),
        jvm =
          RuntimeComponentDto(
            name = property("java.vm.name"),
            version = property("java.vm.version"),
            vendor = property("java.vm.vendor"),
          ),
      ),
    os =
      OperatingSystemInformationDto(
        name = property("os.name"),
        version = property("os.version"),
        arch = property("os.arch"),
      ),
  )

private fun property(name: String): String = System.getProperty(name).orEmpty()

private fun PageHashMatch.matches(dto: PageHashMatchDto): Boolean =
  mediaItemId.value == dto.bookId &&
    pageNumber == dto.pageNumber &&
    fileName == dto.fileName &&
    fileSize == dto.fileSize &&
    mediaType == dto.mediaType

private suspend fun io.ktor.server.application.ApplicationCall.requireResourceAdministrator():
  Boolean {
  if (requireNotNull(principal<KomgaPrincipal>()).user.isAdmin) return true
  respond(HttpStatusCode.Forbidden)
  return false
}
