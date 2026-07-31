package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaNavigationEntry
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadingDirection
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Route.komgaWebPubRoutes(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
  content: BookContentAccess,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    route("/api/v1/books/{bookId}") {
      get("/manifest") {
        call.respondManifest(catalog)
      }
      get("/manifest/divina") {
        call.respondProfileManifest(catalog, MediaProfile.DIVINA)
      }
      get("/manifest/epub") {
        call.respondProfileManifest(catalog, MediaProfile.EPUB)
      }
      get("/manifest/pdf") {
        call.respondProfileManifest(catalog, MediaProfile.PDF)
      }
      get("/positions") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val analyzed = item.media
        if (analyzed?.profile != MediaProfile.EPUB) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        call.respondKomgaCachedJson(
          WEBPUB_JSON.encodeToString(
            R2PositionsDto(
              total = analyzed.positions.size,
              positions = analyzed.positions.map(MediaPosition::toLocatorDto),
            ),
          ),
          POSITION_LIST_CONTENT_TYPE,
        )
      }
      get("/resource/{resource...}") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val analyzed = item.media
        if (analyzed?.profile != MediaProfile.EPUB) {
          call.respond(HttpStatusCode.BadRequest)
          return@get
        }
        val lastModified = analyzed.updatedAtMillis
        call.response.header(
          "Content-Security-Policy",
          "script-src 'none'; object-src 'none';",
        )
        if (call.respondNotModifiedByTimestamp(lastModified)) return@get
        val resource =
          call.parameters.getAll("resource")?.joinToString("/")?.takeIf(String::isNotBlank)
        if (resource == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val opened =
          try {
            content.openResource(bookId, resource)
          } catch (failure: IllegalArgumentException) {
            call.respond(
              HttpStatusCode.NotFound,
              mapOf("error" to (failure.message ?: "EPUB resource is unavailable")),
            )
            return@get
          }
        if (opened == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        try {
          val body = opened.readKomgaCachedBody()
          if (call.respondNotModified(body, lastModified)) return@get
          opened.fileName?.let { fileName ->
            call.response.header(
              HttpHeaders.ContentDisposition,
              ContentDisposition.Inline
                .withParameter(ContentDisposition.Parameters.FileName, fileName)
                .toString(),
            )
          }
          call.respondBytes(
            body.bytes,
            runCatching { ContentType.parse(opened.mediaType) }
              .getOrDefault(ContentType.Application.OctetStream),
          )
        } finally {
          opened.close()
        }
      }
      get("/progression") {
        call.respondProgression(catalog, progress)
      }
      put("/progression") {
        call.updateProgression(catalog, progress)
      }
    }
  }
}

@Serializable
data class WPPublicationDto(
  val context: String,
  val metadata: WPMetadataDto,
  val links: List<WPLinkDto>,
  val images: List<WPLinkDto>,
  val readingOrder: List<WPLinkDto>,
  val resources: List<WPLinkDto>,
  val toc: List<WPLinkDto>,
  val landmarks: List<WPLinkDto>,
  val pageList: List<WPLinkDto>,
)

@Serializable
data class WPLinkDto(
  val title: String? = null,
  val rel: String? = null,
  val href: String? = null,
  val type: String? = null,
  val templated: Boolean? = null,
  val width: Int? = null,
  val height: Int? = null,
  val alternate: List<WPLinkDto> = emptyList(),
  val properties: Map<String, Map<String, String>> = emptyMap(),
  val children: List<WPLinkDto> = emptyList(),
)

@Serializable
data class WPMetadataDto(
  val title: String,
  val identifier: String? = null,
  val type: String? = null,
  val conformsTo: String? = null,
  val sortAs: String? = null,
  val subtitle: String? = null,
  val modified: String? = null,
  val published: String? = null,
  val language: String? = null,
  val author: List<String> = emptyList(),
  val translator: List<String> = emptyList(),
  val editor: List<String> = emptyList(),
  val artist: List<String> = emptyList(),
  val illustrator: List<String> = emptyList(),
  val letterer: List<String> = emptyList(),
  val penciler: List<String> = emptyList(),
  val colorist: List<String> = emptyList(),
  val inker: List<String> = emptyList(),
  val contributor: List<String> = emptyList(),
  val publisher: List<String> = emptyList(),
  val subject: List<String> = emptyList(),
  val readingProgression: String? = null,
  val description: String? = null,
  val numberOfPages: Int? = null,
  val belongsTo: WPBelongsToDto? = null,
  val rendition: Map<String, String> = emptyMap(),
)

@Serializable
data class WPBelongsToDto(
  val series: List<WPContributorDto>,
  val collection: List<WPContributorDto> = emptyList(),
)

@Serializable
data class WPContributorDto(
  val name: String,
  val position: Float? = null,
  val links: List<WPLinkDto> = emptyList(),
)

@Serializable
data class R2DeviceDto(
  val id: String,
  val name: String,
)

@Serializable
data class R2LocationDto(
  val fragments: List<String> = emptyList(),
  val progression: Float? = null,
  val position: Int? = null,
  val totalProgression: Float? = null,
)

@Serializable
data class R2TextDto(
  val after: String? = null,
  val before: String? = null,
  val highlight: String? = null,
)

@Serializable
data class R2LocatorDto(
  val href: String,
  val type: String,
  val title: String? = null,
  val locations: R2LocationDto? = null,
  val text: R2TextDto? = null,
  val koboSpan: String? = null,
)

@Serializable
data class R2ProgressionDto(
  val modified: String,
  val device: R2DeviceDto,
  val locator: R2LocatorDto,
)

@Serializable
data class R2PositionsDto(
  val total: Int,
  val positions: List<R2LocatorDto>,
)

internal suspend fun ApplicationCall.respondManifest(catalog: CatalogReadRepository) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
  if (item == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val analyzed = item.media
  if (analyzed == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val manifest = item.toWebPubManifest(apiBaseUrl())
  if (manifest == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val contentType =
    if (analyzed.profile == MediaProfile.DIVINA) DIVINA_CONTENT_TYPE
    else WEBPUB_CONTENT_TYPE
  respondKomgaCachedJson(WEBPUB_JSON.encodeToString(manifest), contentType)
}

internal suspend fun ApplicationCall.respondProfileManifest(
  catalog: CatalogReadRepository,
  requestedProfile: MediaProfile,
) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
  if (item == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val analyzed = item.media
  val compatible =
    when (requestedProfile) {
      MediaProfile.DIVINA ->
        analyzed?.profile == MediaProfile.DIVINA ||
          analyzed?.profile == MediaProfile.PDF ||
          (analyzed?.profile == MediaProfile.EPUB && analyzed.epubDivinaCompatible)
      else -> analyzed?.profile == requestedProfile
    }
  if (!compatible) {
    respond(
      HttpStatusCode.BadRequest,
      mapOf("error" to "Book media is not compatible with the requested profile"),
    )
    return
  }
  val manifest =
    when (requestedProfile) {
      MediaProfile.DIVINA -> item.toDivinaManifest(apiBaseUrl())
      MediaProfile.EPUB -> item.toEpubManifest(apiBaseUrl())
      MediaProfile.PDF -> item.toPdfManifest(apiBaseUrl())
    }
  respondKomgaCachedJson(
    WEBPUB_JSON.encodeToString(manifest),
    if (requestedProfile == MediaProfile.DIVINA) DIVINA_CONTENT_TYPE
    else WEBPUB_CONTENT_TYPE,
  )
}

private suspend fun ApplicationCall.respondKomgaCachedJson(
  value: String,
  contentType: ContentType,
) {
  val body = value.encodeToByteArray().komgaCachedBody()
  if (respondNotModified(body, lastModifiedMillis = null)) return
  respondBytes(body.bytes, contentType)
}

internal suspend fun ApplicationCall.respondProgression(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  if (catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess()) == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val saved = progress.findBook(bookId, principal.user.id)
  if (saved == null) {
    respond(HttpStatusCode.NoContent)
  } else {
    respondText(
      WEBPUB_JSON.encodeToString(saved.toProgressionDto()),
      PROGRESSION_CONTENT_TYPE,
    )
  }
}

internal suspend fun ApplicationCall.updateProgression(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
  if (item == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val request = receive<R2ProgressionDto>()
  val position = request.locator.locations?.position
  val modified =
    runCatching { Instant.parse(request.modified).toEpochMilli() }.getOrNull()
  if (position == null || modified == null) {
    respond(
      HttpStatusCode.BadRequest,
      mapOf("error" to "Progression requires a valid modified timestamp and position"),
    )
    return
  }
  val locatorJson = WEBPUB_JSON.encodeToString(request.locator)
  try {
    progress.updateBookProgression(
      bookId = bookId,
      userId = principal.user.id,
      page = position,
      modifiedAtMillis = modified,
      deviceId = request.device.id,
      deviceName = request.device.name,
      locatorJson = locatorJson,
    )
    respond(HttpStatusCode.NoContent)
  } catch (failure: IllegalArgumentException) {
    respond(
      HttpStatusCode.BadRequest,
      mapOf("error" to (failure.message ?: "Invalid progression")),
    )
  }
}

internal fun CatalogBook.toWebPubManifest(apiBaseUrl: String): WPPublicationDto? =
  when (media?.profile) {
    MediaProfile.DIVINA -> toDivinaManifest(apiBaseUrl)
    MediaProfile.EPUB -> toEpubManifest(apiBaseUrl)
    MediaProfile.PDF -> toPdfManifest(apiBaseUrl)
    null -> null
  }

private fun CatalogBook.toDivinaManifest(apiBaseUrl: String): WPPublicationDto {
  val analyzed = requireNotNull(media)
  val authorGroups = metadata.authors.groupBy({ it.role.lowercase() }, Author::name)
  val knownRoles =
    setOf(
      "author",
      "translator",
      "editor",
      "artist",
      "illustrator",
      "letterer",
      "penciler",
      "penciller",
      "colorist",
      "inker",
    )
  val emptyProperties = emptyMap<String, Map<String, String>>()
  val pageLinks =
    analyzed.pages.map { page ->
      WPLinkDto(
        href = "$apiBaseUrl/books/${book.id.value}/pages/${page.number}?contentNegotiation=false",
        type =
          if (analyzed.profile == MediaProfile.PDF) "image/jpeg"
          else page.mediaType,
        width = page.dimension?.width,
        height = page.dimension?.height,
        properties = emptyProperties,
      )
    }
  return WPPublicationDto(
    context = "https://readium.org/webpub-manifest/context.jsonld",
    metadata =
      WPMetadataDto(
        title = metadata.title,
        identifier = metadata.isbn.takeIf(String::isNotBlank)?.let { "urn:isbn:$it" },
        conformsTo = "https://readium.org/webpub-manifest/profiles/divina",
        modified = Instant.ofEpochMilli(book.updatedAtMillis).toString(),
        published = metadata.releaseDate,
        language = seriesMetadata.language.takeIf(String::isNotBlank),
        author = authorGroups["author"].orEmpty(),
        translator = authorGroups["translator"].orEmpty(),
        editor = authorGroups["editor"].orEmpty(),
        artist = authorGroups["artist"].orEmpty(),
        illustrator = authorGroups["illustrator"].orEmpty(),
        letterer = authorGroups["letterer"].orEmpty(),
        penciler = authorGroups["penciler"].orEmpty() + authorGroups["penciller"].orEmpty(),
        colorist = authorGroups["colorist"].orEmpty(),
        inker = authorGroups["inker"].orEmpty(),
        contributor =
          metadata.authors.filterNot { it.role.lowercase() in knownRoles }.map(Author::name),
        publisher = emptyList(),
        subject = metadata.tags.toList(),
        readingProgression = this@toDivinaManifest.readingProgression(),
        description = metadata.summary.takeIf(String::isNotBlank),
        numberOfPages = analyzed.pageCount,
        belongsTo =
          WPBelongsToDto(
            series =
              listOf(
                WPContributorDto(
                  name = seriesTitle,
                  position = metadata.numberSort,
                  links = emptyList(),
                ),
              ),
            collection = emptyList(),
          ),
        rendition = emptyMap(),
      ),
    links =
      listOf(
        WPLinkDto(
          rel = "self",
          href = "$apiBaseUrl/books/${book.id.value}/manifest",
          type = DIVINA_MEDIA_TYPE,
          properties = emptyProperties,
        ),
        WPLinkDto(
          rel = "http://opds-spec.org/acquisition",
          href = "$apiBaseUrl/books/${book.id.value}/file",
          type = analyzed.mediaType.toKomgaExportMediaType(),
          properties = emptyProperties,
        ),
      ),
    images = emptyList(),
    readingOrder = pageLinks,
    resources =
      listOf(
        WPLinkDto(
          href = "$apiBaseUrl/books/${book.id.value}/thumbnail",
          type = "image/jpeg",
          properties = emptyProperties,
        ),
      ),
    toc = emptyList(),
    landmarks = emptyList(),
    pageList = emptyList(),
  )
}

private fun CatalogBook.toPdfManifest(apiBaseUrl: String): WPPublicationDto {
  val analyzed = requireNotNull(media)
  val base = toDivinaManifest(apiBaseUrl)
  return base.copy(
    metadata =
      base.metadata.copy(
        conformsTo = "https://readium.org/webpub-manifest/profiles/pdf",
      ),
    links = base.links.replaceSelfType(WEBPUB_MEDIA_TYPE),
    readingOrder =
      List(analyzed.pageCount) { index ->
        WPLinkDto(
          href = "$apiBaseUrl/books/${book.id.value}/pages/${index + 1}/raw",
          type = "application/pdf",
        )
      },
  )
}

private fun CatalogBook.toEpubManifest(apiBaseUrl: String): WPPublicationDto {
  val analyzed = requireNotNull(media)
  val base = toDivinaManifest(apiBaseUrl)
  val resourceBase = "$apiBaseUrl/books/${book.id.value}/resource/"
  return base.copy(
    metadata =
      base.metadata.copy(
        conformsTo = "https://readium.org/webpub-manifest/profiles/epub",
        rendition =
          mapOf(
            "layout" to if (analyzed.epubIsFixedLayout) "fixed" else "reflowable",
          ),
      ),
    links = base.links.replaceSelfType(WEBPUB_MEDIA_TYPE),
    readingOrder =
      analyzed.files.filter { it.kind == MediaFileKind.EPUB_PAGE }.map { file ->
        WPLinkDto(
          href = resourceBase + file.fileName,
          type = file.mediaType,
        )
      },
    resources =
      base.resources +
        // Everything the publication carries that is not a spine page, which is one list however
        // many kinds analysis distinguishes within it: an `EPUB_COVER` is the manifest item the OPF
        // declared as the cover image and is still a resource a reader fetches. Written as "not a
        // page" rather than as a list of asset kinds so that recognising a new kind of file cannot
        // silently drop it from the manifest, which is exactly what naming `EPUB_ASSET` alone did
        // when `EPUB_COVER` was introduced.
        analyzed.files.filter { it.kind != MediaFileKind.EPUB_PAGE }.map { file ->
          WPLinkDto(
            href = resourceBase + file.fileName,
            type = file.mediaType,
          )
        },
    toc = analyzed.toc.map { it.toLinkDto(resourceBase) },
    landmarks = analyzed.landmarks.map { it.toLinkDto(resourceBase) },
    pageList = analyzed.pageList.map { it.toLinkDto(resourceBase) },
  )
}

private fun List<WPLinkDto>.replaceSelfType(mediaType: String): List<WPLinkDto> =
  map { link -> if (link.rel == "self") link.copy(type = mediaType) else link }

private fun MediaNavigationEntry.toLinkDto(resourceBase: String): WPLinkDto =
  WPLinkDto(
    title = title,
    href = href?.let { resourceBase + it },
    children = children.map { it.toLinkDto(resourceBase) },
  )

private fun MediaPosition.toLocatorDto(): R2LocatorDto =
  R2LocatorDto(
    href = href,
    type = mediaType,
    locations =
      R2LocationDto(
        progression = progression,
        position = position,
        totalProgression = totalProgression,
      ),
    koboSpan = koboSpan,
  )

private fun CatalogBook.readingProgression(): String? =
  when (seriesMetadata.readingDirection) {
    ReadingDirection.LEFT_TO_RIGHT -> "ltr"
    ReadingDirection.RIGHT_TO_LEFT -> "rtl"
    ReadingDirection.VERTICAL, ReadingDirection.WEBTOON -> "ttb"
    null -> null
  }

internal fun String?.toKomgaExportMediaType(): String =
  when {
    this == "application/zip" -> "application/vnd.comicbook+zip"
    this?.startsWith("application/x-rar-compressed") == true -> "application/vnd.comicbook-rar"
    else -> orEmpty()
  }

private fun ApplicationCall.apiBaseUrl(): String {
  val origin = request.origin
  val port =
    if (
      (origin.scheme == "http" && origin.serverPort == 80) ||
      (origin.scheme == "https" && origin.serverPort == 443)
    ) {
      ""
    } else {
      ":${origin.serverPort}"
    }
  val apiPath = request.path().substringBefore("/books/")
  return "${origin.scheme}://${origin.serverHost}$port$apiPath"
}

private fun ReadProgress.toProgressionDto(): R2ProgressionDto {
  val locator =
    locatorJson
      ?.let { runCatching { WEBPUB_JSON.decodeFromString<R2LocatorDto>(it) }.getOrNull() }
      ?: R2LocatorDto(href = "", type = "")
  return R2ProgressionDto(
    modified = Instant.ofEpochMilli(readAtMillis).toString(),
    device = R2DeviceDto(deviceId, deviceName),
    locator = locator,
  )
}

private val WEBPUB_JSON =
  Json {
    explicitNulls = false
    ignoreUnknownKeys = true
  }
private val DIVINA_CONTENT_TYPE = ContentType.parse(DIVINA_MEDIA_TYPE)
private val WEBPUB_CONTENT_TYPE = ContentType.parse(WEBPUB_MEDIA_TYPE)
private val POSITION_LIST_CONTENT_TYPE =
  ContentType.parse("application/vnd.readium.position-list+json")
private val PROGRESSION_CONTENT_TYPE =
  ContentType.parse("application/vnd.readium.progression+json")
private const val DIVINA_MEDIA_TYPE: String = "application/divina+json"
private const val WEBPUB_MEDIA_TYPE: String = "application/webpub+json"
