package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.ReadingDirection
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Route.komgaWebPubRoutes(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
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
        call.respondDivinaManifest(catalog)
      }
      get("/manifest/divina") {
        call.respondDivinaManifest(catalog)
      }
      get("/progression") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        if (catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess()) == null) {
          call.respond(HttpStatusCode.NotFound)
          return@get
        }
        val saved = progress.findBook(bookId, principal.user.id)
        if (saved == null) {
          call.respond(HttpStatusCode.NoContent)
        } else {
          call.respondText(
            WEBPUB_JSON.encodeToString(saved.toProgressionDto()),
            PROGRESSION_CONTENT_TYPE,
          )
        }
      }
      put("/progression") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val bookId = BookId(requireNotNull(call.parameters["bookId"]))
        val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
        if (item == null) {
          call.respond(HttpStatusCode.NotFound)
          return@put
        }
        val request = call.receive<R2ProgressionDto>()
        val position = request.locator.locations?.position
        val modified =
          runCatching { Instant.parse(request.modified).toEpochMilli() }.getOrNull()
        if (position == null || modified == null) {
          call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Progression requires a valid modified timestamp and position"),
          )
          return@put
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
          call.respond(HttpStatusCode.NoContent)
        } catch (failure: IllegalStateException) {
          call.respond(
            HttpStatusCode.Conflict,
            mapOf("error" to (failure.message ?: "Stale progression")),
          )
        } catch (failure: IllegalArgumentException) {
          call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (failure.message ?: "Invalid progression")),
          )
        }
      }
    }
  }
}

@Serializable
data class WPPublicationDto(
  @SerialName("@context")
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
  val properties: Map<String, Map<String, String>>,
)

@Serializable
data class WPMetadataDto(
  val title: String,
  val identifier: String? = null,
  @SerialName("@type")
  val type: String? = null,
  val conformsTo: String,
  val sortAs: String? = null,
  val subtitle: String? = null,
  val modified: String,
  val published: String? = null,
  val language: String? = null,
  val author: List<String>,
  val translator: List<String>,
  val editor: List<String>,
  val artist: List<String>,
  val illustrator: List<String>,
  val letterer: List<String>,
  val penciler: List<String>,
  val colorist: List<String>,
  val inker: List<String>,
  val contributor: List<String>,
  val publisher: List<String>,
  val subject: List<String>,
  val readingProgression: String? = null,
  val description: String? = null,
  val numberOfPages: Int,
  val belongsTo: WPBelongsToDto,
  val rendition: Map<String, String>,
)

@Serializable
data class WPBelongsToDto(
  val series: List<WPContributorDto>,
  val collection: List<WPContributorDto>,
)

@Serializable
data class WPContributorDto(
  val name: String,
  val position: Float? = null,
  val links: List<WPLinkDto>,
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

private suspend fun ApplicationCall.respondDivinaManifest(catalog: CatalogReadRepository) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val item = catalog.findBookByIdOrNull(bookId, principal.user.catalogAccess())
  if (item == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  if (item.media?.profile != MediaProfile.DIVINA) {
    respond(
      HttpStatusCode.BadRequest,
      mapOf("error" to "Book media is not compatible with the DiViNa profile"),
    )
    return
  }
  respondText(
    WEBPUB_JSON.encodeToString(item.toDivinaManifest(apiBaseUrl())),
    DIVINA_CONTENT_TYPE,
  )
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
        type = page.mediaType,
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
        publisher =
          seriesMetadata.publisher.takeIf(String::isNotBlank)?.let(::listOf)
            .orEmpty(),
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
          type = analyzed.mediaType,
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

private fun CatalogBook.readingProgression(): String? =
  when (seriesMetadata.readingDirection) {
    ReadingDirection.LEFT_TO_RIGHT -> "ltr"
    ReadingDirection.RIGHT_TO_LEFT -> "rtl"
    ReadingDirection.VERTICAL, ReadingDirection.WEBTOON -> "ttb"
    null -> null
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
private val PROGRESSION_CONTENT_TYPE =
  ContentType.parse("application/vnd.readium.progression+json")
private const val DIVINA_MEDIA_TYPE: String = "application/divina+json"
