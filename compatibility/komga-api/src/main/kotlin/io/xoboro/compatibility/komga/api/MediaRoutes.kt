package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.komgaMediaRoutes(
  catalog: CatalogReadRepository,
  content: BookContentAccess,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/books/{bookId}/pages") {
      get {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        val item = catalog.findBookByIdOrNull(bookId, principal.user.mediaAccess())
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val pages =
          try {
            content.pages(bookId)
          } catch (failure: IllegalArgumentException) {
            call.respond(
              HttpStatusCode.NotFound,
              mapOf("error" to (failure.message ?: "Book media is not ready")),
            )
            return@get
          }
        if (pages == null) {
          call.respond(HttpStatusCode.NotFound)
        } else {
          val body =
            KOMGA_MEDIA_RESPONSE_JSON
              .encodeToString(pages.map(BookPage::toDto))
              .encodeToByteArray()
              .komgaCachedBody()
          if (call.respondNotModified(body, lastModifiedMillis = null)) return@get
          call.respondBytes(body.bytes, ContentType.Application.Json)
        }
      }
      get("/{pageNumber}") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        if (UserRole.PAGE_STREAMING !in principal.user.roles) {
          call.respond(HttpStatusCode.Forbidden)
          return@get
        }
        call.streamPage(catalog, content, principal.user)
      }
      get("/{pageNumber}/raw") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        if (UserRole.PAGE_STREAMING !in principal.user.roles) {
          call.respond(HttpStatusCode.Forbidden)
          return@get
        }
        call.streamPage(catalog, content, principal.user, raw = true)
      }
      get("/{pageNumber}/thumbnail") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        call.streamPage(
          catalog = catalog,
          content = content,
          user = principal.user,
          deliveryRequest =
            PageImageRequest(
              format = PageImageFormat.JPEG,
              maximumDimension = PAGE_THUMBNAIL_MAXIMUM_DIMENSION,
            ),
        )
      }
    }
    route("/api/v1/books/{bookId}/file") {
      get {
        call.streamBook(catalog, content)
      }
      get("/{tail...}") {
        call.streamBook(catalog, content)
      }
    }
  }
}

@Serializable
data class KomgaPageContentDto(
  val number: Int,
  val fileName: String,
  val mediaType: String,
  val width: Int? = null,
  val height: Int? = null,
  val sizeBytes: Long? = null,
  val size: String,
)

private suspend fun io.ktor.server.application.ApplicationCall.streamPage(
  catalog: CatalogReadRepository,
  content: BookContentAccess,
  user: User,
  raw: Boolean = false,
  deliveryRequest: PageImageRequest? = null,
) {
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val item = catalog.findBookByIdOrNull(bookId, user.mediaAccess())
  if (item == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val requested = parameters["pageNumber"]?.toIntOrNull()
  if (requested == null) {
    respond(HttpStatusCode.BadRequest)
    return
  }
  val zeroBased = request.queryParameters["zero_based"]?.toBooleanStrictOrNull() ?: false
  val pageNumber = if (zeroBased && !raw) requested + 1 else requested
  if (raw && item.media?.profile != MediaProfile.PDF) {
    respond(
      HttpStatusCode.BadRequest,
      mapOf("error" to "Raw pages are only available for PDF media"),
    )
    return
  }
  val lastModified = item.media?.updatedAtMillis ?: item.book.updatedAtMillis
  if (respondNotModifiedByTimestamp(lastModified)) return
  val requestedFormat =
    if (raw || deliveryRequest != null) {
      null
    } else {
      when (val convert = request.queryParameters["convert"]) {
        null -> null
        "jpeg" -> PageImageFormat.JPEG
        "png" -> PageImageFormat.PNG
        else -> {
          respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Image conversion must be jpeg or png"),
          )
          return
        }
      }
    }
  val opened =
    try {
      content.openPage(
        bookId,
        pageNumber,
        deliveryRequest ?: PageImageRequest(format = requestedFormat, raw = raw),
      )
    } catch (failure: IllegalArgumentException) {
      respond(
        HttpStatusCode.NotFound,
        mapOf("error" to (failure.message ?: "Book page is unavailable")),
      )
      return
    }
  if (opened == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  opened.useForResponse {
    val type = runCatching { ContentType.parse(it.mediaType) }.getOrDefault(ContentType.Application.OctetStream)
    val body = it.readKomgaCachedBody()
    if (respondNotModified(body, lastModified)) return@useForResponse
    if (deliveryRequest == null) {
      response.header(
        HttpHeaders.ContentDisposition,
        komgaContentDisposition(
          disposition = "inline",
          fileName = "${item.book.name}-$pageNumber${it.mediaType.komgaFileExtension(it.fileName)}",
        ),
      )
    }
    respondBytes(body.bytes, type, HttpStatusCode.OK)
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.streamBook(
  catalog: CatalogReadRepository,
  content: BookContentAccess,
) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  if (UserRole.FILE_DOWNLOAD !in principal.user.roles) {
    respond(HttpStatusCode.Forbidden)
    return
  }
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  if (catalog.findBookByIdOrNull(bookId, principal.user.mediaAccess()) == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val opened =
    try {
      content.openBook(bookId)
    } catch (failure: IllegalArgumentException) {
      respond(
        HttpStatusCode.NotFound,
        mapOf("error" to (failure.message ?: "Book file is unavailable")),
      )
      return
    }
  if (opened == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  opened.useForResponse { stream ->
    val totalLength = stream.contentLength
    val requestedRange = request.headers[HttpHeaders.Range]
    val range =
      if (requestedRange == null) {
        null
      } else {
        totalLength?.let { parseByteRange(requestedRange, it) }
      }
    if (requestedRange != null && range == null) {
      if (totalLength != null) {
        response.header(HttpHeaders.ContentRange, "bytes */$totalLength")
      }
      respond(HttpStatusCode.RequestedRangeNotSatisfiable)
      return@useForResponse
    }
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header(HttpHeaders.CacheControl, KOMGA_PRIVATE_REVALIDATE)
    stream.fileName?.let { fileName ->
      response.header(
        HttpHeaders.ContentDisposition,
        komgaContentDisposition("attachment", fileName),
      )
    }
    if (range != null) {
      response.header(
        HttpHeaders.ContentRange,
        "bytes ${range.first}-${range.last}/$totalLength",
      )
      check(stream.skip(range.first) == range.first) {
        "Book stream ended before the requested range"
      }
    }
    val responseLength = range?.length ?: totalLength
    val type =
      runCatching { ContentType.parse(stream.mediaType) }
        .getOrDefault(ContentType.Application.OctetStream)
    respondOutputStream(
      contentType = type,
      status = if (range == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
      contentLength = responseLength,
    ) {
      val buffer = ByteArray(STREAM_BUFFER_SIZE)
      var remaining = responseLength
      while (remaining == null || remaining > 0) {
        val maximum =
          remaining
            ?.coerceAtMost(buffer.size.toLong())
            ?.toInt()
            ?: buffer.size
        val read = stream.read(buffer, length = maximum)
        if (read < 0) break
        if (read > 0) {
          write(buffer, 0, read)
          remaining = remaining?.minus(read)
        }
      }
    }
  }
}

internal fun komgaContentDisposition(
  disposition: String,
  fileName: String,
): String {
  require(disposition == "inline" || disposition == "attachment")
  val encodedWord =
    fileName.encodeToByteArray().joinToString("") { byte ->
      when (val value = byte.toInt() and 0xff) {
        0x20 -> "_"
        in 0x21..0x7e ->
          if (
            value == '='.code ||
            value == '?'.code ||
            value == '_'.code ||
            value == '"'.code ||
            value == '\\'.code
          ) {
            "=${value.toHex()}"
          } else {
            value.toChar().toString()
          }
        else -> "=${value.toHex()}"
      }
    }
  val encodedParameter =
    fileName.encodeToByteArray().joinToString("") { byte ->
      val value = byte.toInt() and 0xff
      if (
        value in 'a'.code..'z'.code ||
        value in 'A'.code..'Z'.code ||
        value in '0'.code..'9'.code ||
        value.toChar() in RFC_5987_SAFE
      ) {
        value.toChar().toString()
      } else {
        "%${value.toHex()}"
      }
    }
  return "$disposition; filename=\"=?UTF-8?Q?$encodedWord?=\"; " +
    "filename*=UTF-8''$encodedParameter"
}

private fun String.komgaFileExtension(fileName: String?): String =
  when (substringBefore(';').lowercase()) {
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "application/pdf" -> ".pdf"
    else -> fileName?.substringAfterLast('.', "")?.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
  }

private fun Int.toHex(): String = HEX[(this ushr 4) and 0xf].toString() + HEX[this and 0xf]

private data class ByteRange(
  val first: Long,
  val last: Long,
) {
  val length: Long = last - first + 1
}

private fun parseByteRange(
  header: String,
  totalLength: Long,
): ByteRange? {
  if (totalLength <= 0 || !header.startsWith("bytes=")) return null
  val value = header.removePrefix("bytes=")
  if (',' in value) return null
  val parts = value.split('-', limit = 2)
  if (parts.size != 2) return null
  val firstText = parts[0].trim()
  val lastText = parts[1].trim()
  if (firstText.isEmpty()) {
    val suffixLength = lastText.toLongOrNull() ?: return null
    if (suffixLength <= 0) return null
    val first = (totalLength - suffixLength).coerceAtLeast(0)
    return ByteRange(first, totalLength - 1)
  }
  val first = firstText.toLongOrNull() ?: return null
  if (first < 0 || first >= totalLength) return null
  val last =
    if (lastText.isEmpty()) {
      totalLength - 1
    } else {
      (lastText.toLongOrNull() ?: return null).coerceAtMost(totalLength - 1)
    }
  if (last < first) return null
  return ByteRange(first, last)
}

private suspend inline fun MediaContentStream.useForResponse(
  block: suspend (MediaContentStream) -> Unit,
) {
  try {
    block(this)
  } finally {
    close()
  }
}

private fun User.mediaAccess(): CatalogAccess =
  CatalogAccess(
    userId = id,
    libraryIds = if (canAccessAllLibraries()) null else sharedLibraryIds,
    restrictions = restrictions,
  )

private fun BookPage.toDto(): KomgaPageContentDto =
  KomgaPageContentDto(
    number = number,
    fileName = fileName,
    mediaType = mediaType,
    width = dimension?.width,
    height = dimension?.height,
    sizeBytes = fileSize,
    size = fileSize?.let(::formatPageSize).orEmpty(),
  )

private fun formatPageSize(bytes: Long): String =
  if (bytes < 1_024) "$bytes B" else "${bytes / 1_024} KiB"

private const val STREAM_BUFFER_SIZE = 8 * 1_024
private const val PAGE_THUMBNAIL_MAXIMUM_DIMENSION = 300
private const val RFC_5987_SAFE = "!#$&+-.^_`|~"
private const val HEX = "0123456789ABCDEF"
private val KOMGA_MEDIA_RESPONSE_JSON = Json { explicitNulls = false }
