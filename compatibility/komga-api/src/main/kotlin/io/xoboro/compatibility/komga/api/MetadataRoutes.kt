package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.ManualBookMetadataPatch
import io.xoboro.core.application.ManualSeriesMetadataPatch
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacet
import io.xoboro.core.application.MetadataFacetQuery
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.PatchField
import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.WebLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun Route.komgaMetadataRoutes(
  editing: MetadataEditingLifecycle,
  facets: MetadataFacetRepository,
) {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    patch("/api/v1/books/{bookId}/metadata") {
      if (!call.requireMetadataAdministrator()) return@patch
      val patch = call.receivePatch(JsonObject::toBookMetadataPatch) ?: return@patch
      val result =
        runCatching {
          editing.patchBook(BookId(requireNotNull(call.parameters["bookId"])), patch)
        }
      call.respondMetadataMutation(result)
    }
    patch("/api/v1/books/metadata") {
      if (!call.requireMetadataAdministrator()) return@patch
      val patches =
        call.receivePatch { raw ->
          raw.map { (id, value) -> BookId(id) to value.jsonObject.toBookMetadataPatch() }.toMap()
        } ?: return@patch
      editing.patchBooks(patches)
      call.respond(HttpStatusCode.NoContent)
    }
    patch("/api/v1/series/{seriesId}/metadata") {
      if (!call.requireMetadataAdministrator()) return@patch
      val patch = call.receivePatch(JsonObject::toSeriesMetadataPatch) ?: return@patch
      val result =
        runCatching {
          editing.patchSeries(SeriesId(requireNotNull(call.parameters["seriesId"])), patch)
        }
      call.respondMetadataMutation(result)
    }

    get("/api/v1/authors") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      call.respond(
        facets
          .findAuthors(
            call.metadataFacetQuery(),
            principal.user.catalogAccess(),
            CatalogPageRequest(unpaged = true),
          ).content
          .map { KomgaAuthorDto(it.name, it.role) },
      )
    }
    get("/api/v2/authors") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val page =
        facets.findAuthors(
          call.metadataFacetQuery(),
          principal.user.catalogAccess(),
          call.catalogPageRequest(),
        )
      call.respond(page.toPageDto(page.content.map { KomgaAuthorDto(it.name, it.role) }))
    }
    get("/api/v1/authors/names") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val authors =
        facets.findAuthors(
          call.metadataFacetQuery(),
          principal.user.catalogAccess(),
          CatalogPageRequest(unpaged = true),
        )
      call.respond(authors.content.map(Author::normalizedName).distinct().sorted())
    }
    get("/api/v1/authors/roles") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val authors =
        facets.findAuthors(
          MetadataFacetQuery(),
          principal.user.catalogAccess(),
          CatalogPageRequest(unpaged = true),
        )
      call.respond(authors.content.map(Author::normalizedRole).distinct().sorted())
    }
    get("/api/v1/genres") {
      call.respondFacet(facets, MetadataFacet.GENRE)
    }
    get("/api/v1/sharing-labels") {
      call.respondFacet(facets, MetadataFacet.SHARING_LABEL)
    }
    get("/api/v1/tags") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      val query = call.metadataFacetQuery()
      call.respond(
        (
          facets.findValues(MetadataFacet.BOOK_TAG, query, principal.user.catalogAccess()) +
            facets.findValues(MetadataFacet.SERIES_TAG, query, principal.user.catalogAccess())
        ).distinct().sorted(),
      )
    }
    get("/api/v1/tags/book") {
      call.respondFacet(facets, MetadataFacet.BOOK_TAG)
    }
    get("/api/v1/tags/series") {
      call.respondFacet(facets, MetadataFacet.SERIES_TAG)
    }
    get("/api/v1/languages") {
      call.respondFacet(facets, MetadataFacet.LANGUAGE)
    }
    get("/api/v1/publishers") {
      call.respondFacet(facets, MetadataFacet.PUBLISHER)
    }
    get("/api/v1/age-ratings") {
      call.respondFacet(facets, MetadataFacet.AGE_RATING)
    }
    get("/api/v1/series/release-dates") {
      call.respondFacet(facets, MetadataFacet.RELEASE_YEAR)
    }
  }
}

private suspend fun ApplicationCall.respondFacet(
  facets: MetadataFacetRepository,
  facet: MetadataFacet,
) {
  val principal = requireNotNull(principal<KomgaPrincipal>())
  respond(facets.findValues(facet, metadataFacetQuery(), principal.user.catalogAccess()))
}

private fun ApplicationCall.metadataFacetQuery(): MetadataFacetQuery =
  MetadataFacetQuery(
    libraryIds = request.queryParameters.getAll("library_id").orEmpty().map(::LibraryId).toSet(),
    collectionId = request.queryParameters["collection_id"]?.let(::CollectionId),
    seriesId = request.queryParameters["series_id"]?.let(::SeriesId),
    readListId = request.queryParameters["readlist_id"]?.let(::ReadListId),
    search = request.queryParameters["search"],
    role = request.queryParameters["role"],
  )

private suspend fun ApplicationCall.requireMetadataAdministrator(): Boolean {
  if (requireNotNull(principal<KomgaPrincipal>()).user.isAdmin) return true
  respondError(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
  return false
}

private suspend fun <T> ApplicationCall.receivePatch(transform: (JsonObject) -> T): T? =
  runCatching { transform(receive()) }
    .getOrElse {
      respond(
        HttpStatusCode.BadRequest,
        ValidationErrorResponse(
          listOf(ViolationDto("metadata", it.message ?: "invalid value")),
        ),
      )
      null
    }

private suspend fun ApplicationCall.respondMetadataMutation(result: Result<*>) {
  result.fold(
    onSuccess = { respond(HttpStatusCode.NoContent) },
    onFailure = {
      val notFound = it is IllegalArgumentException && it.message?.endsWith("not found") == true
      if (notFound) {
        respond(HttpStatusCode.NotFound)
      } else {
        respond(
          HttpStatusCode.BadRequest,
          ValidationErrorResponse(
            listOf(ViolationDto("metadata", it.message ?: "invalid value")),
          ),
        )
      }
    },
  )
}

private fun JsonObject.toBookMetadataPatch(): ManualBookMetadataPatch =
  ManualBookMetadataPatch(
    title = optionalString("title"),
    titleLock = optionalBoolean("titleLock"),
    summary = patchField("summary", JsonElement::nullableString),
    summaryLock = optionalBoolean("summaryLock"),
    number = optionalString("number"),
    numberLock = optionalBoolean("numberLock"),
    numberSort = optionalFloat("numberSort"),
    numberSortLock = optionalBoolean("numberSortLock"),
    releaseDate = patchField("releaseDate", JsonElement::nullableString),
    releaseDateLock = optionalBoolean("releaseDateLock"),
    authors = patchField("authors") { it.nullableObjects { toAuthor() } },
    authorsLock = optionalBoolean("authorsLock"),
    tags = patchField("tags") { it.nullableStrings()?.toSet() },
    tagsLock = optionalBoolean("tagsLock"),
    isbn = patchField("isbn", JsonElement::nullableString),
    isbnLock = optionalBoolean("isbnLock"),
    links = patchField("links") { it.nullableObjects { toWebLink() } },
    linksLock = optionalBoolean("linksLock"),
  )

private fun JsonObject.toSeriesMetadataPatch(): ManualSeriesMetadataPatch =
  ManualSeriesMetadataPatch(
    status = optionalEnum<SeriesStatus>("status"),
    statusLock = optionalBoolean("statusLock"),
    title = optionalString("title"),
    titleLock = optionalBoolean("titleLock"),
    titleSort = optionalString("titleSort"),
    titleSortLock = optionalBoolean("titleSortLock"),
    summary = optionalString("summary"),
    summaryLock = optionalBoolean("summaryLock"),
    readingDirection = patchField("readingDirection") { it.nullableEnum<ReadingDirection>() },
    readingDirectionLock = optionalBoolean("readingDirectionLock"),
    publisher = optionalString("publisher"),
    publisherLock = optionalBoolean("publisherLock"),
    ageRating = patchField("ageRating", JsonElement::nullableInt),
    ageRatingLock = optionalBoolean("ageRatingLock"),
    language = optionalString("language"),
    languageLock = optionalBoolean("languageLock"),
    genres = patchField("genres") { it.nullableStrings()?.toSet() },
    genresLock = optionalBoolean("genresLock"),
    tags = patchField("tags") { it.nullableStrings()?.toSet() },
    tagsLock = optionalBoolean("tagsLock"),
    totalBookCount = patchField("totalBookCount", JsonElement::nullableInt),
    totalBookCountLock = optionalBoolean("totalBookCountLock"),
    sharingLabels = patchField("sharingLabels") { it.nullableStrings()?.toSet() },
    sharingLabelsLock = optionalBoolean("sharingLabelsLock"),
    links = patchField("links") { it.nullableObjects { toWebLink() } },
    linksLock = optionalBoolean("linksLock"),
    alternateTitles = patchField("alternateTitles") { it.nullableObjects { toAlternateTitle() } },
    alternateTitlesLock = optionalBoolean("alternateTitlesLock"),
  )

private fun JsonObject.optionalString(name: String): String? =
  get(name)?.takeUnless { it is JsonNull }?.requiredString()

private fun JsonObject.optionalBoolean(name: String): Boolean? =
  get(name)?.takeUnless { it is JsonNull }?.let { element ->
    requireNotNull(element.jsonPrimitive.booleanOrNull) { "$name must be a boolean" }
  }

private fun JsonObject.optionalFloat(name: String): Float? =
  get(name)?.takeUnless { it is JsonNull }?.let { element ->
    requireNotNull(element.jsonPrimitive.floatOrNull) { "$name must be a number" }
  }

private inline fun <reified T : Enum<T>> JsonObject.optionalEnum(name: String): T? =
  get(name)?.takeUnless { it is JsonNull }?.requiredString()?.let {
    enumValueOf<T>(it)
  }

private fun <T> JsonObject.patchField(
  name: String,
  converter: (JsonElement) -> T?,
): PatchField<T> =
  if (containsKey(name)) PatchField(present = true, value = converter(requireNotNull(get(name))))
  else PatchField()

private fun JsonElement.nullableString(): String? =
  takeUnless { it is JsonNull }?.requiredString()

private fun JsonElement.nullableInt(): Int? =
  takeUnless { it is JsonNull }?.let { element ->
    requireNotNull(element.jsonPrimitive.intOrNull) { "Expected an integer" }
  }

private inline fun <reified T : Enum<T>> JsonElement.nullableEnum(): T? =
  nullableString()?.let { enumValueOf<T>(it) }

private fun JsonElement.nullableStrings(): List<String>? =
  takeUnless { it is JsonNull }?.let { element ->
    (element as? JsonArray)?.map(JsonElement::requiredString)
      ?: error("Expected an array")
  }

private fun <T> JsonElement.nullableObjects(converter: JsonObject.() -> T): List<T>? =
  takeUnless { it is JsonNull }?.let { element ->
    (element as? JsonArray)?.map { it.jsonObject.converter() } ?: error("Expected an array")
  }

private fun JsonObject.toAuthor(): Author =
  Author(
    name = requireNotNull(optionalString("name")),
    role = requireNotNull(optionalString("role")),
  )

private fun JsonObject.toWebLink(): WebLink =
  WebLink(
    label = requireNotNull(optionalString("label")),
    url = requireNotNull(optionalString("url")),
  )

private fun JsonObject.toAlternateTitle(): AlternateTitle =
  AlternateTitle(
    label = requireNotNull(optionalString("label")),
    title = requireNotNull(optionalString("title")),
  )

private fun JsonElement.requiredString(): String {
  val primitive = this as? JsonPrimitive ?: error("Expected a string")
  require(primitive.isString) { "Expected a string" }
  return primitive.content
}
