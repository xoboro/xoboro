package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.User
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.komgaArtworkRoutes(
  artwork: ArtworkLifecycle,
  catalog: CatalogReadRepository,
  content: BookContentAccess,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    artworkOwnerRoutes(
      prefix = "/api/v1/books/{bookId}",
      kind = ArtworkOwnerKind.MEDIA_ITEM,
      idParameter = "bookId",
      artwork = artwork,
      content = content,
      visible = { owner, user ->
        catalog.findBookByIdOrNull(BookId(owner.id), user.catalogAccess()) != null
      },
      fallbackBook = { owner, _ -> BookId(owner.id) },
    )
    artworkOwnerRoutes(
      prefix = "/api/v1/series/{seriesId}",
      kind = ArtworkOwnerKind.SERIES,
      idParameter = "seriesId",
      artwork = artwork,
      content = content,
      visible = { owner, user ->
        catalog.findSeriesByIdOrNull(SeriesId(owner.id), user.catalogAccess()) != null
      },
      fallbackBook = { owner, user -> catalog.firstVisibleBook(SeriesId(owner.id), user) },
    )
    artworkOwnerRoutes(
      prefix = "/api/v1/collections/{id}",
      kind = ArtworkOwnerKind.COLLECTION,
      idParameter = "id",
      artwork = artwork,
      content = content,
      visible = { owner, user ->
        collections.findByIdOrNull(CollectionId(owner.id))?.let { collection ->
          collection.seriesIds.any {
            catalog.findSeriesByIdOrNull(it, user.catalogAccess()) != null
          } || (user.isAdmin && collection.seriesIds.isEmpty())
        } == true
      },
      fallbackBook = { owner, user ->
        collections
          .findByIdOrNull(CollectionId(owner.id))
          ?.seriesIds
          ?.firstNotNullOfOrNull { catalog.firstVisibleBook(it, user) }
      },
    )
    artworkOwnerRoutes(
      prefix = "/api/v1/readlists/{id}",
      kind = ArtworkOwnerKind.READ_LIST,
      idParameter = "id",
      artwork = artwork,
      content = content,
      visible = { owner, user ->
        readLists.findByIdOrNull(ReadListId(owner.id))?.let { readList ->
          readList.bookIds.any {
            catalog.findBookByIdOrNull(it, user.catalogAccess()) != null
          } || (user.isAdmin && readList.bookIds.isEmpty())
        } == true
      },
      fallbackBook = { owner, user ->
        readLists
          .findByIdOrNull(ReadListId(owner.id))
          ?.bookIds
          ?.firstOrNull { catalog.findBookByIdOrNull(it, user.catalogAccess()) != null }
      },
    )
  }
}

private fun Route.artworkOwnerRoutes(
  prefix: String,
  kind: ArtworkOwnerKind,
  idParameter: String,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
  visible: (ArtworkOwner, User) -> Boolean,
  fallbackBook: (ArtworkOwner, User) -> BookId?,
) {
  route(prefix) {
    get("/thumbnail") {
      val owner = call.visibleArtworkOwner(kind, idParameter, visible) ?: return@get
      val selectedContent = artwork.selectedContentOrNull(owner)
      if (selectedContent == null) {
        val user = requireNotNull(call.principal<KomgaPrincipal>()).user
        call.respondFallbackArtwork(
          content = content,
          bookId = fallbackBook(owner, user),
          cacheControl = kind.selectedArtworkCacheControl(),
        )
      } else {
        val body = selectedContent.bytes.komgaCachedBody()
        if (
          call.respondNotModified(
            body = body,
            lastModifiedMillis = null,
            cacheControl = kind.selectedArtworkCacheControl(),
          )
        ) {
          return@get
        }
        call.respondBytes(
          body.bytes,
          ContentType.parse(selectedContent.artwork.mediaType),
        )
      }
    }
    get("/thumbnails") {
      val owner = call.visibleArtworkOwner(kind, idParameter, visible) ?: return@get
      val body =
        ARTWORK_RESPONSE_JSON
          .encodeToString(artwork.findAll(owner).map(Artwork::toDto))
          .encodeToByteArray()
          .komgaCachedBody()
      if (call.respondNotModified(body, lastModifiedMillis = null)) return@get
      call.respondBytes(body.bytes, ContentType.Application.Json)
    }
    get("/thumbnails/{thumbnailId}") {
      val owner = call.visibleArtworkOwner(kind, idParameter, visible) ?: return@get
      val content =
        artwork.contentOrNull(
          owner,
          ArtworkId(requireNotNull(call.parameters["thumbnailId"])),
        )
      if (content == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        val body = content.bytes.komgaCachedBody()
        if (call.respondNotModified(body, lastModifiedMillis = null)) return@get
        call.respondBytes(
          body.bytes,
          ContentType.parse(content.artwork.mediaType),
        )
      }
    }
    post("/thumbnails") {
      if (!call.requireArtworkAdministrator()) return@post
      val owner = call.visibleArtworkOwner(kind, idParameter, visible) ?: return@post
      val input = call.receiveArtworkUpload() ?: return@post
      val selected =
        call.request.queryParameters["selected"]?.toBooleanStrictOrNull() ?: true
      val created =
        runCatching { artwork.addUploaded(owner, input, selected) }
          .getOrElse {
            call.respond(
              HttpStatusCode.UnsupportedMediaType,
              mapOf("error" to (it.message ?: "Unsupported artwork")),
            )
            return@post
          }
      call.respond(created.toDto())
    }
    put("/thumbnails/{thumbnailId}/selected") {
      if (!call.requireArtworkAdministrator()) return@put
      val owner = call.visibleArtworkOwner(kind, idParameter, visible) ?: return@put
      val selected =
        artwork.markSelected(
          owner,
          ArtworkId(requireNotNull(call.parameters["thumbnailId"])),
        )
      call.respond(if (selected) HttpStatusCode.Accepted else HttpStatusCode.NotFound)
    }
    delete("/thumbnails/{thumbnailId}") {
      if (!call.requireArtworkAdministrator()) return@delete
      val owner = call.visibleArtworkOwner(kind, idParameter, visible) ?: return@delete
      val deleted =
        runCatching {
          artwork.deleteUploaded(
            owner,
            ArtworkId(requireNotNull(call.parameters["thumbnailId"])),
          )
        }.getOrElse {
          call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (it.message ?: "Artwork cannot be deleted")),
          )
          return@delete
        }
      call.respond(if (deleted) HttpStatusCode.Accepted else HttpStatusCode.NotFound)
    }
  }
}

private fun CatalogReadRepository.firstVisibleBook(
  seriesId: SeriesId,
  user: User,
): BookId? =
  findBooks(
    query = BookCatalogQuery(seriesId = seriesId, deleted = false),
    access = user.catalogAccess(),
    page = CatalogPageRequest(size = 1, sorts = listOf(CatalogSort("numberSort"))),
  ).content.firstOrNull()?.book?.id

private suspend fun ApplicationCall.respondFallbackArtwork(
  content: BookContentAccess,
  bookId: BookId?,
  cacheControl: String,
) {
  if (bookId == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val opened =
    runCatching {
      content.openPage(
        bookId,
        pageNumber = 1,
        request =
          PageImageRequest(
            format = PageImageFormat.JPEG,
            maximumDimension = 1_600,
          ),
      )
    }.getOrNull()
  if (opened == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  try {
    val body = opened.readKomgaCachedBody()
    if (respondNotModified(body, lastModifiedMillis = null, cacheControl = cacheControl)) {
      return
    }
    respondBytes(body.bytes, ContentType.Image.JPEG, HttpStatusCode.OK)
  } finally {
    opened.close()
  }
}

private fun ArtworkOwnerKind.selectedArtworkCacheControl(): String =
  when (this) {
    ArtworkOwnerKind.COLLECTION,
    ArtworkOwnerKind.READ_LIST,
    -> KOMGA_PRIVATE_ONE_HOUR
    ArtworkOwnerKind.MEDIA_ITEM,
    ArtworkOwnerKind.SERIES,
    -> KOMGA_PRIVATE_REVALIDATE
  }

private suspend fun ApplicationCall.visibleArtworkOwner(
  kind: ArtworkOwnerKind,
  idParameter: String,
  visible: (ArtworkOwner, User) -> Boolean,
): ArtworkOwner? {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  val owner = ArtworkOwner(kind, requireNotNull(parameters[idParameter]))
  if (!visible(owner, principal.user)) {
    respond(HttpStatusCode.NotFound)
    return null
  }
  return owner
}

private suspend fun ApplicationCall.receiveArtworkUpload(): ByteArray? {
  var bytes: ByteArray? = null
  receiveMultipart(formFieldLimit = ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES.toLong()).forEachPart {
      part ->
    try {
      if (part is PartData.FileItem && part.name == "file" && bytes == null) {
        bytes =
          part.provider()
            .readRemaining(ArtworkLifecycle.MAXIMUM_UPLOAD_BYTES.toLong() + 1)
            .readByteArray()
      }
    } finally {
      part.release()
    }
  }
  if (bytes == null || bytes.isEmpty()) {
    respond(HttpStatusCode.BadRequest, mapOf("error" to "A non-empty file part is required"))
    return null
  }
  return bytes
}

private suspend fun ApplicationCall.requireArtworkAdministrator(): Boolean {
  if (requireNotNull(principal<KomgaPrincipal>()).user.isAdmin) return true
  respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
  return false
}

@Serializable
data class KomgaArtworkDto(
  val id: String,
  val bookId: String? = null,
  val seriesId: String? = null,
  val collectionId: String? = null,
  val readListId: String? = null,
  val type: String,
  val selected: Boolean,
  val mediaType: String,
  val fileSize: Long,
  val width: Int,
  val height: Int,
)

private const val KOMGA_PRIVATE_ONE_HOUR = "max-age=3600, private"
private val ARTWORK_RESPONSE_JSON = Json { explicitNulls = false }

private fun Artwork.toDto(): KomgaArtworkDto =
  KomgaArtworkDto(
    id = id.value,
    bookId = owner.id.takeIf { owner.kind == ArtworkOwnerKind.MEDIA_ITEM },
    seriesId = owner.id.takeIf { owner.kind == ArtworkOwnerKind.SERIES },
    collectionId = owner.id.takeIf { owner.kind == ArtworkOwnerKind.COLLECTION },
    readListId = owner.id.takeIf { owner.kind == ArtworkOwnerKind.READ_LIST },
    type = type.name,
    selected = selected,
    mediaType = mediaType,
    fileSize = fileSize,
    width = width,
    height = height,
  )
