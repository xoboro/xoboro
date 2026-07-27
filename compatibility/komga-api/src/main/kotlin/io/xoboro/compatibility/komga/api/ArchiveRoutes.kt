package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserRole
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun Route.komgaArchiveRoutes(
  catalog: CatalogReadRepository,
  readLists: ReadListRepository,
  content: BookContentAccess,
) {
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
      val seriesId = SeriesId(requireNotNull(call.parameters["seriesId"]))
      val series = catalog.findSeriesByIdOrNull(seriesId, principal.user.catalogAccess())
      if (series == null) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      val books =
        catalog.findBooks(
          query = BookCatalogQuery(seriesId = seriesId),
          access = principal.user.catalogAccess(),
          page =
            CatalogPageRequest(
              sorts = listOf(CatalogSort("numberSort")),
              unpaged = true,
            ),
        ).content
      call.streamArchive(
        fileName = "${series.metadata.title}.zip",
        books = books.map { ArchiveMember(it) },
        content = content,
      )
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
      val books =
        readList.bookIds.mapIndexedNotNull { index, bookId ->
          catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
            ?.let { ArchiveMember(it, prefix = index + 1) }
        }
      call.streamArchive(
        fileName = "${readList.name}.zip",
        books = books,
        content = content,
      )
    }
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.streamArchive(
  fileName: String,
  books: List<ArchiveMember>,
  content: BookContentAccess,
) {
  response.header(
    HttpHeaders.ContentDisposition,
    ContentDisposition.Attachment
      .withParameter(ContentDisposition.Parameters.FileName, fileName.safeArchiveName())
      .toString(),
  )
  respondOutputStream(
    contentType = ContentType.parse("application/zip"),
    status = HttpStatusCode.OK,
  ) {
    ZipOutputStream(this).use { archive ->
      archive.setLevel(Deflater.NO_COMPRESSION)
      val usedNames = mutableSetOf<String>()
      books.forEach { member ->
        val opened = content.openBook(member.book.book.id) ?: return@forEach
        opened.useForArchive { stream ->
          val leaf =
            stream.fileName
              ?.substringAfterLast('/')
              ?.substringAfterLast('\\')
              ?.safeArchiveName()
              ?.takeIf(String::isNotBlank)
              ?: "${member.book.book.id.value}.bin"
          val requestedName = member.prefix?.let { "$it - $leaf" } ?: leaf
          val entryName = requestedName.uniqueArchiveName(usedNames)
          archive.putNextEntry(ZipEntry(entryName))
          val buffer = ByteArray(ARCHIVE_BUFFER_SIZE)
          while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) archive.write(buffer, 0, read)
          }
          archive.closeEntry()
        }
      }
    }
  }
}

private inline fun <T> io.xoboro.core.application.MediaContentStream.useForArchive(
  block: (io.xoboro.core.application.MediaContentStream) -> T,
): T =
  try {
    block(this)
  } finally {
    close()
  }

private fun String.safeArchiveName(): String =
  replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "archive.zip" }

private fun String.uniqueArchiveName(used: MutableSet<String>): String {
  if (used.add(this)) return this
  val stem = substringBeforeLast('.', this)
  val extension = substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
  var index = 2
  while (!used.add("$stem ($index)$extension")) index += 1
  return "$stem ($index)$extension"
}

private data class ArchiveMember(
  val book: CatalogBook,
  val prefix: Int? = null,
)

private const val ARCHIVE_BUFFER_SIZE: Int = 64 * 1_024
