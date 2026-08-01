package io.xoboro.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
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

/**
 * Archive downloads for a whole series or read list.
 *
 * A native client could previously download one media item at a time only, so "download this series"
 * was reachable through the Komga-compatible surface and not through this one. The assembly, naming
 * and access rules are shared with that surface through [MediaArchivePlanner] and
 * [MediaArchiveWriter] rather than copied - see their documentation for why the access filter belongs
 * behind that boundary rather than in either route.
 *
 * Gated on `FILE_DOWNLOAD`, the same permission a single media item's `/file` requires: an archive is
 * the original files, so a caller who may not have one may not have a container full of them either.
 * The role is checked before any catalog read, so a caller without it learns nothing about which
 * identifiers exist.
 */
fun Route.xoboroNativeArchiveRoutes(
  catalog: CatalogReadRepository,
  readLists: ReadListRepository,
  content: BookContentAccess,
) {
  val planner = MediaArchivePlanner(catalog)
  val writer = MediaArchiveWriter(content)
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      get("/series/{seriesId}/file") {
        val user = call.nativeUser()
        if (UserRole.FILE_DOWNLOAD !in user.roles) {
          call.respondFileDownloadForbidden()
          return@get
        }
        val plan =
          planner.seriesArchive(
            seriesId = SeriesId(call.requiredParameter("seriesId")),
            access = user.catalogAccess(),
          )
        if (plan == null) {
          call.respondNativeNotFound("series_not_found", "Series was not found")
          return@get
        }
        // A visible series whose items are all hidden from this caller streams an empty archive
        // rather than refusing: the series itself was found, and answering not-found for it would
        // contradict what `/series/{seriesId}` says about the same identifier.
        call.respondNativeArchive(plan, writer)
      }
      get("/read-lists/{readListId}/file") {
        val user = call.nativeUser()
        if (UserRole.FILE_DOWNLOAD !in user.roles) {
          call.respondFileDownloadForbidden()
          return@get
        }
        val readList =
          readLists.findByIdOrNull(ReadListId(call.requiredParameter("readListId")))
        val plan = readList?.let { planner.readListArchive(it, user.catalogAccess()) }
        // A read list is visible through its members, so one with nothing visible in it is answered
        // exactly as a missing one - the same rule every other native read-list route follows, and
        // the reason a caller cannot probe read-list identifiers with this endpoint.
        if (plan == null || plan.members.isEmpty()) {
          call.respondNativeNotFound("read_list_not_found", "Read list was not found")
          return@get
        }
        call.respondNativeArchive(plan, writer)
      }
    }
  }
}

/**
 * Streams the archive.
 *
 * No `Content-Length`, `ETag` or `Accept-Ranges`: the container is assembled as it is written, so its
 * length is not known when the headers go out, and it has no validator that would survive a member's
 * file changing. A resumable download of an archive would have to re-derive byte offsets from a
 * container this server does not keep, so a client resumes by requesting the members individually
 * through `/media-items/{mediaItemId}/file`, which does support ranges.
 */
private suspend fun ApplicationCall.respondNativeArchive(
  plan: MediaArchivePlan,
  writer: MediaArchiveWriter,
) {
  response.header(
    HttpHeaders.ContentDisposition,
    nativeDownloadContentDisposition(plan.fileName),
  )
  respondOutputStream(contentType = ARCHIVE_CONTENT_TYPE, status = HttpStatusCode.OK) {
    writer.write(plan.members, this)
  }
}

private val ARCHIVE_CONTENT_TYPE = ContentType("application", "zip")
