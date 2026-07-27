package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import io.xoboro.core.application.ReadListImportBookMatch
import io.xoboro.core.application.ReadListImportBookMatches
import io.xoboro.core.application.ReadListImportException
import io.xoboro.core.application.ReadListImportLifecycle
import io.xoboro.core.application.ReadListImportMatch
import io.xoboro.core.application.ReadListImportSeriesMatch
import io.xoboro.core.domain.UserRole
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

fun Route.komgaComicRackRoutes(imports: ReadListImportLifecycle) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    post("/api/v1/readlists/match/comicrack") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (UserRole.ADMIN !in principal.user.roles) {
        call.respond(HttpStatusCode.Forbidden)
        return@post
      }
      val bytes = call.receiveComicRackUpload() ?: return@post
      try {
        call.respond(imports.match(bytes).toDto())
      } catch (failure: ReadListImportException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to failure.code))
      } catch (failure: IllegalArgumentException) {
        call.respond(
          HttpStatusCode.BadRequest,
          mapOf("error" to (failure.message ?: "ERR_1015")),
        )
      }
    }
  }
}

@Serializable
data class ReadListRequestMatchDto(
  val readListMatch: ReadListMatchDto,
  val requests: List<ReadListRequestBookMatchesDto>,
  val errorCode: String = "",
)

@Serializable
data class ReadListMatchDto(
  val name: String,
  val errorCode: String = "",
)

@Serializable
data class ReadListRequestBookMatchesDto(
  val request: ReadListRequestBookDto,
  val matches: List<ReadListRequestBookMatchDto>,
)

@Serializable
data class ReadListRequestBookDto(
  val series: Set<String>,
  val number: String,
)

@Serializable
data class ReadListRequestBookMatchDto(
  val series: ReadListRequestBookMatchSeriesDto,
  val books: List<ReadListRequestBookMatchBookDto>,
)

@Serializable
data class ReadListRequestBookMatchSeriesDto(
  val seriesId: String,
  val title: String,
  val releaseDate: String? = null,
)

@Serializable
data class ReadListRequestBookMatchBookDto(
  val bookId: String,
  val number: String,
  val title: String,
)

private fun ReadListImportMatch.toDto(): ReadListRequestMatchDto =
  ReadListRequestMatchDto(
    readListMatch = ReadListMatchDto(readList.name, readList.errorCode),
    requests = requests.map(ReadListImportBookMatches::toDto),
    errorCode = errorCode,
  )

private fun ReadListImportBookMatches.toDto(): ReadListRequestBookMatchesDto =
  ReadListRequestBookMatchesDto(
    request = ReadListRequestBookDto(request.series, request.number),
    matches = matches.map(ReadListImportSeriesMatch::toDto),
  )

private fun ReadListImportSeriesMatch.toDto(): ReadListRequestBookMatchDto =
  ReadListRequestBookMatchDto(
    series = ReadListRequestBookMatchSeriesDto(seriesId, title, releaseDate),
    books = books.map(ReadListImportBookMatch::toDto),
  )

private fun ReadListImportBookMatch.toDto(): ReadListRequestBookMatchBookDto =
  ReadListRequestBookMatchBookDto(bookId, number, title)

private suspend fun io.ktor.server.application.ApplicationCall.receiveComicRackUpload(): ByteArray? {
  var bytes: ByteArray? = null
  receiveMultipart(
    formFieldLimit = ReadListImportLifecycle.MAXIMUM_UPLOAD_BYTES.toLong(),
  ).forEachPart { part ->
    try {
      if (part is PartData.FileItem && part.name == "file" && bytes == null) {
        bytes =
          part.provider()
            .readRemaining(ReadListImportLifecycle.MAXIMUM_UPLOAD_BYTES.toLong() + 1)
            .readByteArray()
      }
    } finally {
      part.release()
    }
  }
  if (
    bytes == null ||
      bytes.isEmpty() ||
      bytes.size > ReadListImportLifecycle.MAXIMUM_UPLOAD_BYTES
  ) {
    respond(HttpStatusCode.BadRequest, mapOf("error" to "ERR_1015"))
    return null
  }
  return bytes
}
