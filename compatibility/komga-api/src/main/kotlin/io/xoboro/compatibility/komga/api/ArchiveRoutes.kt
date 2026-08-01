package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.MediaArchivePlan
import io.xoboro.core.application.MediaArchivePlanner
import io.xoboro.core.application.MediaArchiveWriter
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserRole

fun Route.komgaArchiveRoutes(
  catalog: CatalogReadRepository,
  readLists: ReadListRepository,
  content: BookContentAccess,
) {
  val planner = MediaArchivePlanner(catalog)
  val writer = MediaArchiveWriter(content)
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    get("/api/v1/series/{seriesId}/file") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (UserRole.FILE_DOWNLOAD !in principal.user.roles) {
        call.respond(HttpStatusCode.Forbidden)
        return@get
      }
      val plan =
        planner.seriesArchive(
          seriesId = SeriesId(requireNotNull(call.parameters["seriesId"])),
          access = principal.user.catalogAccess(),
        )
      if (plan == null) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      call.streamArchive(plan, writer)
    }

    get("/api/v1/readlists/{id}/file") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (UserRole.FILE_DOWNLOAD !in principal.user.roles) {
        call.respond(HttpStatusCode.Forbidden)
        return@get
      }
      val readList =
        readLists.findByIdOrNull(ReadListId(requireNotNull(call.parameters["id"])))
      if (readList == null) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      // An archive with no members is answered as an empty container here rather than as a
      // not-found. Komga answers 200 for a read list the caller can see but whose books it cannot,
      // and this surface is frozen; the native surface hides that case instead.
      call.streamArchive(planner.readListArchive(readList, principal.user.catalogAccess()), writer)
    }
  }
}

private suspend fun ApplicationCall.streamArchive(
  plan: MediaArchivePlan,
  writer: MediaArchiveWriter,
) {
  response.header(
    HttpHeaders.ContentDisposition,
    ContentDisposition.Attachment
      .withParameter(ContentDisposition.Parameters.FileName, plan.fileName)
      .toString(),
  )
  respondOutputStream(
    contentType = ContentType.parse("application/zip"),
    status = HttpStatusCode.OK,
  ) {
    writer.write(plan.members, this)
  }
}
