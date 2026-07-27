package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.xoboro.core.application.BookImportCommand
import io.xoboro.core.application.CatalogFileLifecycleRequester
import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserRole
import kotlinx.serialization.Serializable

fun Route.komgaFileLifecycleRoutes(files: CatalogFileLifecycleRequester) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    post("/api/v1/books/import") {
      if (!call.requireFileAdministrator()) return@post
      val request = call.receive<BookImportBatchDto>()
      request.books.forEach { book ->
        runCatching {
          files.importBooks(
            books =
              listOf(
                BookImportCommand(
                  sourceFile = book.sourceFile,
                  seriesId = SeriesId(book.seriesId),
                  upgradeBookId = book.upgradeBookId?.let(::BookId),
                  destinationName = book.destinationName,
                ),
              ),
            copyMode = request.copyMode,
          )
        }
      }
      call.respond(HttpStatusCode.Accepted)
    }

    delete("/api/v1/books/{bookId}/file") {
      if (!call.requireFileAdministrator()) return@delete
      files.deleteBook(BookId(requireNotNull(call.parameters["bookId"])))
      call.respond(HttpStatusCode.Accepted)
    }

    delete("/api/v1/series/{seriesId}/file") {
      if (!call.requireFileAdministrator()) return@delete
      files.deleteSeries(SeriesId(requireNotNull(call.parameters["seriesId"])))
      call.respond(HttpStatusCode.Accepted)
    }
  }
}

@Serializable
data class BookImportBatchDto(
  val books: List<BookImportDto> = emptyList(),
  val copyMode: SourceCopyMode,
)

@Serializable
data class BookImportDto(
  val sourceFile: String,
  val seriesId: String,
  val upgradeBookId: String? = null,
  val destinationName: String? = null,
)

private suspend fun io.ktor.server.application.ApplicationCall.requireFileAdministrator(): Boolean {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  if (UserRole.ADMIN in principal.user.roles) return true
  respond(HttpStatusCode.Forbidden)
  return false
}
