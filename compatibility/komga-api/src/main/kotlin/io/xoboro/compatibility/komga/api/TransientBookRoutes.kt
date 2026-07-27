package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.TransientBook
import io.xoboro.core.application.TransientBookLifecycle
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.UserRole
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

fun Route.komgaTransientBookRoutes(transientBooks: TransientBookLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    post("/api/v1/transient-books") {
      if (!call.requireTransientAdministrator()) return@post
      try {
        call.respond(transientBooks.scan(call.receive<TransientScanRequestDto>().path).map(TransientBook::toDto))
      } catch (failure: IllegalArgumentException) {
        call.respond(
          HttpStatusCode.BadRequest,
          mapOf("error" to (failure.message ?: "Invalid transient book path")),
        )
      }
    }

    post("/api/v1/transient-books/{id}/analyze") {
      if (!call.requireTransientAdministrator()) return@post
      val analyzed =
        try {
          transientBooks.analyze(requireNotNull(call.parameters["id"]))
        } catch (failure: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (failure.message ?: "Transient book analysis failed")),
          )
          return@post
        }
      if (analyzed == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        call.respond(analyzed.toDto())
      }
    }

    get("/api/v1/transient-books/{id}/pages/{pageNumber}") {
      if (!call.requireTransientAdministrator()) return@get
      val id = requireNotNull(call.parameters["id"])
      val transient = transientBooks.findByIdOrNull(id)
      val media = transient?.media
      if (media == null || media.status != MediaStatus.READY) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      val pageNumber = call.parameters["pageNumber"]?.toIntOrNull()
      if (pageNumber == null || pageNumber !in 1..media.pageCount) {
        call.respond(HttpStatusCode.BadRequest)
        return@get
      }
      val content =
        try {
          transientBooks.openPage(id, pageNumber)
        } catch (failure: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (failure.message ?: "Transient book page is invalid")),
          )
          return@get
        }
      if (content == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        call.respondTransientContent(content)
      }
    }
  }
}

@Serializable
data class TransientScanRequestDto(
  val path: String,
)

@Serializable
data class TransientBookDto(
  val id: String,
  val name: String,
  val url: String,
  val sizeBytes: Long,
  val size: String,
  val fileLastModified: String,
  val status: String,
  val mediaType: String,
  val pages: List<TransientPageDto>,
  val files: List<String>,
  val comment: String,
  val number: Float? = null,
  val seriesId: String? = null,
)

@Serializable
data class TransientPageDto(
  val number: Int,
  val fileName: String,
  val mediaType: String,
  val width: Int? = null,
  val height: Int? = null,
  val sizeBytes: Long? = null,
  val size: String,
)

private fun TransientBook.toDto(): TransientBookDto =
  TransientBookDto(
    id = id,
    name = name,
    url = path,
    sizeBytes = sizeBytes,
    size = sizeBytes.toTransientSize(),
    fileLastModified =
      LocalDateTime
        .ofInstant(Instant.ofEpochMilli(fileLastModifiedMillis), ZoneId.systemDefault())
        .toString(),
    status = media?.status?.name ?: MediaStatus.UNKNOWN.name,
    mediaType = media?.mediaType.orEmpty(),
    pages = media?.pages?.map(BookPage::toTransientDto).orEmpty(),
    files = media?.files?.map { it.fileName }.orEmpty(),
    comment = media?.comment.orEmpty(),
  )

private fun BookPage.toTransientDto(): TransientPageDto =
  TransientPageDto(
    number = number,
    fileName = fileName,
    mediaType = mediaType,
    width = dimension?.width,
    height = dimension?.height,
    sizeBytes = fileSize,
    size = fileSize?.toTransientSize().orEmpty(),
  )

private fun Long.toTransientSize(): String =
  if (this < 1_024) "$this B" else "${this / 1_024} KiB"

private suspend fun io.ktor.server.application.ApplicationCall.requireTransientAdministrator(): Boolean {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  if (UserRole.ADMIN in principal.user.roles) return true
  respond(HttpStatusCode.Forbidden)
  return false
}

private suspend fun io.ktor.server.application.ApplicationCall.respondTransientContent(
  content: MediaContentStream,
) {
  try {
    val body = content.readKomgaCachedBody()
    if (respondNotModified(body, lastModifiedMillis = null)) return
    respondBytes(body.bytes, ContentType.parse(content.mediaType))
  } finally {
    content.close()
  }
}
