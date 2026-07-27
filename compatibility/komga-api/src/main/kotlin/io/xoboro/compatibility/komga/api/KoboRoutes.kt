package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.ApiKeyPrincipal
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.MediaSyncChange
import io.xoboro.core.application.MediaSyncChangeKind
import io.xoboro.core.application.MediaSyncLifecycle
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.SyncPointId
import io.xoboro.core.domain.SyncReadListState
import io.xoboro.core.domain.UserRole
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.komgaKoboRoutes(
  apiKeys: ApiKeyLifecycle,
  sync: MediaSyncLifecycle,
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
  kepub: KepubContentAccess? = null,
  syncItemLimit: Int = DEFAULT_KOBO_SYNC_ITEM_LIMIT,
) {
  require(syncItemLimit > 0) { "Kobo sync item limit must be positive" }
  route("/kobo/{authToken}") {
    get("/ping") {
      call.koboPrincipal(apiKeys) ?: return@get
      call.respondText("pong")
    }
    get("/v1/initialization") {
      call.koboPrincipal(apiKeys) ?: return@get
      call.response.header("x-kobo-apitoken", "e30=")
      call.respondKobo(call.initializationDocument(call.requireKoboToken()))
    }
    post("/v1/auth/device") {
      call.koboPrincipal(apiKeys) ?: return@post
      val request = call.receive<KoboDeviceAuthRequestDto>()
      call.respondKobo(
        KoboAuthDto(
          accessToken = secureToken(),
          refreshToken = secureToken(),
          trackingId = UUID.randomUUID().toString(),
          userKey = request.userKey,
        ),
      )
    }
    get("/v1/library/sync") {
      val principal = call.koboPrincipal(apiKeys) ?: return@get
      call.respondKoboSync(
        principal = principal,
        sync = sync,
        catalog = catalog,
        token = call.request.headers[KOBO_SYNC_TOKEN_HEADER]?.toKomgaKoboSyncToken()
          ?: KomgaKoboSyncToken(),
        limit = syncItemLimit,
        kepubAvailable = kepub?.isAvailable() == true,
      )
    }
    get("/v1/library/{bookId}/metadata") {
      val principal = call.koboPrincipal(apiKeys) ?: return@get
      val book = call.visibleKoboBook(catalog, principal) ?: return@get
      call.respondKobo(
        JsonArray(listOf(call.koboMetadata(book, kepub?.isAvailable() == true))),
      )
    }
    get("/v1/library/{bookId}/state") {
      val principal = call.koboPrincipal(apiKeys) ?: return@get
      val book = call.visibleKoboBook(catalog, principal) ?: return@get
      call.respondKobo(JsonArray(listOf(book.toKoboReadingState())))
    }
    put("/v1/library/{bookId}/state") {
      val principal = call.koboPrincipal(apiKeys) ?: return@put
      val book = call.visibleKoboBook(catalog, principal) ?: return@put
      call.updateKoboState(book, principal, progress)
    }
    get("/v1/books/{bookId}/file/epub") {
      val principal = call.koboPrincipal(apiKeys) ?: return@get
      if (UserRole.FILE_DOWNLOAD !in principal.user.roles && !principal.user.isAdmin) {
        call.respond(HttpStatusCode.Forbidden)
        return@get
      }
      val book = call.visibleKoboBook(catalog, principal) ?: return@get
      val convert = call.request.queryParameters["convert_kepub"]?.toBooleanStrictOrNull() == true
      val opened =
        if (convert && book.media?.epubIsKepub != true) {
          kepub?.openKepub(
            book.book.id,
            "${book.book.fileModifiedAtMillis}-${book.book.updatedAtMillis}-${book.media?.updatedAtMillis}",
          )
        } else {
          content.openBook(book.book.id)
        }
      if (opened == null) {
        call.respond(
          if (convert) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NotFound,
        )
      } else {
        call.streamKoboFile(opened, book, convert)
      }
    }
    get("/v1/books/{thumbnailId}/thumbnail/{width}/{height}/{isGreyScale}/image.jpg") {
      val principal = call.koboPrincipal(apiKeys) ?: return@get
      call.respondKoboThumbnail(principal, catalog, artwork, content)
    }
    get("/v1/books/{thumbnailId}/thumbnail/{width}/{height}/{quality}/{isGreyScale}/image.jpg") {
      val principal = call.koboPrincipal(apiKeys) ?: return@get
      call.respondKoboThumbnail(principal, catalog, artwork, content)
    }
    route("/{path...}") {
      get { call.respondKoboCatchAll(apiKeys) }
      post { call.respondKoboCatchAll(apiKeys) }
      put { call.respondKoboCatchAll(apiKeys) }
      patch { call.respondKoboCatchAll(apiKeys) }
      delete { call.respondKoboCatchAll(apiKeys) }
    }
  }
}

interface KepubContentAccess {
  fun isAvailable(): Boolean

  fun openKepub(
    bookId: BookId,
    revision: String,
  ): MediaContentStream?
}

private suspend fun ApplicationCall.respondKoboSync(
  principal: ApiKeyPrincipal,
  sync: MediaSyncLifecycle,
  catalog: CatalogReadRepository,
  token: KomgaKoboSyncToken,
  limit: Int,
  kepubAvailable: Boolean,
) {
  val user = principal.user
  val ongoing =
    sync.pointOrNull(token.ongoingSyncPointId?.let(::SyncPointId), user.id)
  val current =
    ongoing ?: sync.capture(user.id, principal.apiKey.id, user.catalogAccess())
  val previousId =
    token.lastSuccessfulSyncPointId
      ?.let(::SyncPointId)
      ?.takeIf { sync.pointOrNull(it, user.id) != null }
  val plan = sync.plan(previousId, current.id, user.id)
  val batch = plan.changes.drop(current.cursor).take(limit)
  val nextCursor = current.cursor + batch.size
  val hasMore = nextCursor < plan.changes.size
  val results =
    batch.flatMap {
      renderKoboChange(it, plan, catalog, principal, kepubAvailable)
    }
  val updatedToken =
    if (hasMore) {
      sync.advance(current.id, nextCursor)
      token.copy(ongoingSyncPointId = current.id.value)
    } else {
      sync.complete(previousId, current.id)
      token.copy(
        ongoingSyncPointId = null,
        lastSuccessfulSyncPointId = current.id.value,
      )
    }
  if (hasMore) response.header(KOBO_SYNC_HEADER, "continue")
  response.header(KOBO_SYNC_TOKEN_HEADER, updatedToken.toKoboHeader())
  respondKobo(JsonArray(results))
}

private fun ApplicationCall.renderKoboChange(
  change: MediaSyncChange,
  plan: io.xoboro.core.application.MediaSyncPlan,
  catalog: CatalogReadRepository,
  principal: ApiKeyPrincipal,
  kepubAvailable: Boolean,
): List<JsonElement> =
  when (change.kind) {
    MediaSyncChangeKind.MEDIA_ITEM_ADDED,
    MediaSyncChangeKind.MEDIA_ITEM_CHANGED,
    -> {
      val book =
        catalog.findBookByIdOrNull(
          requireNotNull(change.mediaItemId),
          principal.user.catalogAccess(),
        ) ?: return emptyList()
      buildList {
        add(wrapper("NewEntitlement", entitlementContainer(book, kepubAvailable)))
        if (change.kind == MediaSyncChangeKind.MEDIA_ITEM_CHANGED) {
          add(wrapper("ChangedProductMetadata", koboMetadata(book, kepubAvailable)))
          if (book.readProgress != null) {
            add(
              wrapper(
                "ChangedReadingState",
                buildJsonObject {
                  put("ReadingState", book.toKoboReadingState())
                },
              ),
            )
          }
        }
      }
    }
    MediaSyncChangeKind.MEDIA_ITEM_REMOVED -> {
      val id = requireNotNull(change.mediaItemId)
      val old = plan.from?.mediaItems?.firstOrNull { it.mediaItemId == id }
      listOf(wrapper("ChangedEntitlement", removedEntitlementContainer(id, old?.createdAtMillis ?: 0)))
    }
    MediaSyncChangeKind.PROGRESS_CHANGED -> {
      val book =
        catalog.findBookByIdOrNull(
          requireNotNull(change.mediaItemId),
          principal.user.catalogAccess(),
        ) ?: return emptyList()
      listOf(
        wrapper(
          "ChangedReadingState",
          buildJsonObject {
            put("ReadingState", book.toKoboReadingState())
          },
        ),
      )
    }
    MediaSyncChangeKind.READ_LIST_ADDED,
    MediaSyncChangeKind.READ_LIST_CHANGED,
    -> {
      val state =
        plan.to.readLists.firstOrNull { it.readListId == change.readListId }
          ?: return emptyList()
      val key =
        if (change.kind == MediaSyncChangeKind.READ_LIST_ADDED) "NewTag" else "ChangedTag"
      listOf(wrapper(key, state.toKoboWrappedTag()))
    }
    MediaSyncChangeKind.READ_LIST_REMOVED -> {
      val state =
        plan.from?.readLists?.firstOrNull { it.readListId == change.readListId }
          ?: return emptyList()
      listOf(wrapper("DeletedTag", state.toKoboWrappedTag(includeItems = false)))
    }
  }

private fun ApplicationCall.entitlementContainer(
  book: CatalogBook,
  kepubAvailable: Boolean,
): JsonObject =
  buildJsonObject {
    put("BookEntitlement", book.toKoboEntitlement(isRemoved = false))
    put("BookMetadata", koboMetadata(book, kepubAvailable))
    put("ReadingState", book.toKoboReadingState())
  }

private fun removedEntitlementContainer(
  id: BookId,
  createdAtMillis: Long,
): JsonObject =
  buildJsonObject {
    put(
      "BookEntitlement",
      entitlement(id, createdAtMillis, createdAtMillis, isRemoved = true),
    )
    put(
      "BookMetadata",
      buildJsonObject {
        put("Categories", JsonArray(listOf(JsonPrimitive(KOBO_DUMMY_ID))))
        put("CoverImageId", id.value)
        put("CrossRevisionId", id.value)
        put("EntitlementId", id.value)
        put("Genre", KOBO_DUMMY_ID)
        put("RevisionId", id.value)
        put("Title", id.value)
        put("WorkId", id.value)
      },
    )
  }

private fun CatalogBook.toKoboEntitlement(isRemoved: Boolean): JsonObject =
  entitlement(
    book.id,
    book.createdAtMillis,
    maxOf(book.updatedAtMillis, metadata.updatedAtMillis),
    isRemoved,
  )

private fun entitlement(
  id: BookId,
  createdAtMillis: Long,
  updatedAtMillis: Long,
  isRemoved: Boolean,
): JsonObject =
  buildJsonObject {
    put("Accessibility", "Full")
    put("ActivePeriod", buildJsonObject { put("From", createdAtMillis.isoInstant()) })
    put("Created", createdAtMillis.isoInstant())
    put("CrossRevisionId", id.value)
    put("Id", id.value)
    put("IsHiddenFromArchive", false)
    put("IsLocked", false)
    put("IsRemoved", isRemoved)
    put("LastModified", updatedAtMillis.isoInstant())
    put("OriginCategory", "Imported")
    put("RevisionId", id.value)
    put("Status", "Active")
  }

private fun ApplicationCall.koboMetadata(
  book: CatalogBook,
  kepubAvailable: Boolean,
): JsonObject {
  val (format, convert) =
    when {
      book.media?.epubIsFixedLayout == true -> "EPUB3FL" to false
      book.media?.epubIsKepub == true -> "KEPUB" to false
      kepubAvailable -> "KEPUB" to true
      else -> "EPUB3" to false
    }
  val downloadUrl =
    koboUrl(
      "/kobo/${requireKoboToken()}/v1/books/${book.book.id.value}/file/epub" +
        "?convert_kepub=$convert",
    )
  return buildJsonObject {
    put("Categories", JsonArray(listOf(JsonPrimitive(KOBO_DUMMY_ID))))
    put(
      "ContributorRoles",
      JsonArray(
        book.metadata.authors.map { author ->
          buildJsonObject { put("Name", author.name) }
        },
      ),
    )
    put("Contributors", JsonArray(book.metadata.authors.map { JsonPrimitive(it.name) }))
    put("CoverImageId", book.book.id.value)
    put("CrossRevisionId", book.book.id.value)
    put("CurrentDisplayPrice", zeroAmount("USD"))
    put("CurrentLoveDisplayPrice", zeroAmount())
    put("Description", book.metadata.summary.ifBlank { " " })
    put(
      "DownloadUrls",
      buildJsonArray {
        add(
          buildJsonObject {
            put("DrmType", "None")
            put("Format", format)
            put("Size", book.book.fileSize)
            put("Platform", "Generic")
            put("Url", downloadUrl)
          },
        )
      },
    )
    put("EntitlementId", book.book.id.value)
    put("ExternalIds", JsonArray(emptyList()))
    put("Genre", KOBO_DUMMY_ID)
    put("IsEligibleForKoboLove", false)
    put("IsInternetArchive", false)
    put("IsPreOrder", false)
    put("IsSocialEnabled", true)
    book.metadata.isbn.takeIf(String::isNotBlank)?.let { put("Isbn", it) }
    put("Language", book.seriesMetadata.language.take(2).ifBlank { "en" })
    put("PhoneticPronunciations", JsonObject(emptyMap()))
    put(
      "PublicationDate",
      book.metadata.releaseDate?.let { "${it}T00:00:00Z" }
        ?: book.book.createdAtMillis.isoInstant(),
    )
    put(
      "Publisher",
      buildJsonObject {
        put("Imprint", "")
        put("Name", book.seriesMetadata.publisher)
      },
    )
    put("RevisionId", book.book.id.value)
    if (!book.book.oneshot) {
      put(
        "Series",
        buildJsonObject {
          put("Id", book.book.seriesId.value)
          put("Name", book.seriesMetadata.title)
          put("Number", book.metadata.number)
          put("NumberFloat", book.metadata.numberSort)
        },
      )
    }
    put("Title", book.metadata.title)
    put("WorkId", book.book.id.value)
  }
}

private fun CatalogBook.toKoboReadingState(): JsonObject {
  val saved = readProgress
  val modified = (saved?.readAtMillis ?: book.createdAtMillis).isoInstant()
  val locator =
    saved?.locatorJson?.let {
      runCatching { KOBO_JSON.decodeFromString<R2LocatorDto>(it) }.getOrNull()
    }
  val status =
    when {
      saved?.completed == true -> "Finished"
      saved != null -> "Reading"
      else -> "ReadyToRead"
    }
  return buildJsonObject {
    put("Created", (saved?.createdAtMillis ?: book.createdAtMillis).isoInstant())
    put(
      "CurrentBookmark",
      buildJsonObject {
        put("LastModified", modified)
        locator?.locations?.totalProgression?.let { put("ProgressPercent", it * 100) }
        locator?.locations?.progression?.let {
          put("ContentSourceProgressPercent", it * 100)
        }
        locator?.let {
          put(
            "Location",
            buildJsonObject {
              it.koboSpan?.let { value -> put("Value", value) }
              put("Type", "KoboSpan")
              put("Source", it.href)
            },
          )
        }
      },
    )
    put("EntitlementId", this@toKoboReadingState.book.id.value)
    put("LastModified", modified)
    put("PriorityTimestamp", modified)
    put("Statistics", buildJsonObject { put("LastModified", modified) })
    put(
      "StatusInfo",
      buildJsonObject {
        put("LastModified", modified)
        put("Status", status)
        put("TimesStartedReading", if (saved == null) 0 else 1)
      },
    )
  }
}

private fun SyncReadListState.toKoboWrappedTag(
  includeItems: Boolean = true,
): JsonObject =
  buildJsonObject {
    put(
      "Tag",
      buildJsonObject {
        put("Id", readListId.value)
        put("Created", createdAtMillis.isoInstant())
        put("LastModified", updatedAtMillis.isoInstant())
        put("Name", name)
        put("Type", "UserTag")
        if (includeItems) {
          put(
            "Items",
            JsonArray(
              mediaItemIds.map {
                buildJsonObject {
                  put("RevisionId", it.value)
                  put("Type", "ProductRevisionTagItem")
                }
              },
            ),
          )
        }
      },
    )
  }

private suspend fun ApplicationCall.updateKoboState(
  book: CatalogBook,
  principal: ApiKeyPrincipal,
  progress: ReadProgressLifecycle,
) {
  val request = receive<KoboReadingStateUpdateDto>()
  val update = request.readingStates.firstOrNull()
  val location = update?.currentBookmark?.location
  val resourceProgress = update?.currentBookmark?.contentSourceProgressPercent
  if (update == null || location == null || resourceProgress == null) {
    respond(HttpStatusCode.BadRequest)
    return
  }
  val positions = book.media?.positions.orEmpty()
  val position =
    if (update.statusInfo.status == KoboStatusDto.FINISHED) {
      positions.lastOrNull()
    } else {
      positions.resolveKoboPosition(location, resourceProgress / 100)
    }
  val modified = runCatching { Instant.parse(update.lastModified).toEpochMilli() }.getOrNull()
  if (position == null || modified == null) {
    respond(HttpStatusCode.BadRequest)
    return
  }
  val locator =
    R2LocatorDto(
      href = position.href,
      type = position.mediaType,
      locations =
        R2LocationDto(
          progression = position.progression,
          position = position.position,
          totalProgression = position.totalProgression,
        ),
      koboSpan = position.koboSpan,
    )
  val success =
    runCatching {
      progress.updateBookProgression(
        bookId = book.book.id,
        userId = principal.user.id,
        page = position.position,
        modifiedAtMillis = modified,
        deviceId = principal.apiKey.id.value,
        deviceName = principal.apiKey.comment,
        locatorJson = KOBO_JSON.encodeToString(locator),
      )
    }.isSuccess
  val result = if (success) KoboResultDto.SUCCESS else KoboResultDto.FAILURE
  respondKobo(
    KoboRequestResultDto(
      requestResult = result,
      updateResults =
        listOf(
          KoboReadingStateUpdateResultDto(
            entitlementId = book.book.id.value,
            currentBookmarkResult = KoboWrappedResultDto(result),
            statisticsResult =
              KoboWrappedResultDto(
                if (success) KoboResultDto.IGNORED else KoboResultDto.FAILURE,
              ),
            statusInfoResult = KoboWrappedResultDto(result),
          ),
        ),
    ),
  )
}

private fun List<io.xoboro.core.domain.MediaPosition>.resolveKoboPosition(
  location: KoboLocationDto,
  progression: Float,
): io.xoboro.core.domain.MediaPosition? {
  if (location.type.equals("KoboSpan", ignoreCase = true) && location.value != null) {
    firstOrNull { it.koboSpan == location.value }?.let { return it }
  }
  val resourcePositions = filter { it.href == location.source }
  if (resourcePositions.isEmpty()) return null
  val index = (progression.coerceIn(0F, 1F) * (resourcePositions.size - 1)).toInt()
  return resourcePositions[index]
}

private suspend fun ApplicationCall.visibleKoboBook(
  catalog: CatalogReadRepository,
  principal: ApiKeyPrincipal,
): CatalogBook? {
  val bookId = parameters["bookId"] ?: parameters["thumbnailId"]
  val item =
    bookId
      ?.let(::BookId)
      ?.let { catalog.findBookByIdOrNull(it, principal.user.catalogAccess()) }
      ?.takeIf { it.media?.profile == MediaProfile.EPUB }
  if (item == null) respond(HttpStatusCode.NotFound)
  return item
}

private suspend fun ApplicationCall.respondKoboThumbnail(
  principal: ApiKeyPrincipal,
  catalog: CatalogReadRepository,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
) {
  val book = visibleKoboBook(catalog, principal) ?: return
  val width = parameters["width"]?.toIntOrNull()
  val height = parameters["height"]?.toIntOrNull()
  val maximumDimension = listOfNotNull(width, height).maxOrNull()?.coerceIn(1, 4_096) ?: 1_600
  val selected =
    artwork.selectedContentOrNull(
      ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, book.book.id.value),
    )
  if (selected != null) {
    val body = selected.bytes.komgaCachedBody()
    if (respondNotModified(body, lastModifiedMillis = null)) return
    respondBytes(body.bytes, ContentType.parse(selected.artwork.mediaType))
    return
  }
  val page =
    content.openPage(
      book.book.id,
      1,
      PageImageRequest(
        format = PageImageFormat.JPEG,
        maximumDimension = maximumDimension,
      ),
    )
  if (page == null) {
    respond(HttpStatusCode.NotFound)
  } else {
    try {
      val body = page.readKomgaCachedBody()
      if (respondNotModified(body, lastModifiedMillis = null)) return
      respondBytes(body.bytes, ContentType.Image.JPEG)
    } finally {
      page.close()
    }
  }
}

private suspend fun ApplicationCall.streamKoboFile(
  opened: MediaContentStream,
  book: CatalogBook,
  converted: Boolean,
) {
  val baseName = book.book.relativePath.substringAfterLast('/').substringBeforeLast('.')
  val extension = if (converted) ".kepub.epub" else ".epub"
  response.header(
    HttpHeaders.ContentDisposition,
    ContentDisposition.Attachment
      .withParameter(ContentDisposition.Parameters.FileName, "$baseName$extension")
      .toString(),
  )
  stream(opened, inline = false)
}

private suspend fun ApplicationCall.stream(
  opened: MediaContentStream,
  inline: Boolean,
) {
  if (inline && opened.fileName != null) {
    response.header(
      HttpHeaders.ContentDisposition,
      ContentDisposition.Inline
        .withParameter(ContentDisposition.Parameters.FileName, opened.fileName!!)
        .toString(),
    )
  }
  try {
    respondOutputStream(
      contentType = runCatching { ContentType.parse(opened.mediaType) }.getOrNull(),
      contentLength = opened.contentLength,
    ) {
      val buffer = ByteArray(KOBO_STREAM_BUFFER_SIZE)
      while (true) {
        val count = opened.read(buffer)
        if (count < 0) break
        if (count > 0) write(buffer, 0, count)
      }
    }
  } finally {
    opened.close()
  }
}

private suspend fun ApplicationCall.respondKoboCatchAll(apiKeys: ApiKeyLifecycle) {
  koboPrincipal(apiKeys) ?: return
  respondKobo(JsonObject(emptyMap()))
}

private suspend fun ApplicationCall.koboPrincipal(apiKeys: ApiKeyLifecycle): ApiKeyPrincipal? {
  val token = parameters["authToken"].orEmpty()
  val principal = apiKeys.authenticate(token)
  if (principal == null) {
    respond(HttpStatusCode.Unauthorized)
    return null
  }
  if (UserRole.KOBO_SYNC !in principal.user.roles && !principal.user.isAdmin) {
    respond(HttpStatusCode.Forbidden)
    return null
  }
  return principal
}

private fun ApplicationCall.requireKoboToken(): String =
  requireNotNull(parameters["authToken"]) { "Kobo route requires an authentication token" }

private fun ApplicationCall.initializationDocument(token: String): JsonObject =
  buildJsonObject {
    put(
      "Resources",
      buildJsonObject {
        put("device_auth", koboUrl("/kobo/$token/v1/auth/device"))
        put("library_sync", koboUrl("/kobo/$token/v1/library/sync"))
        put("library_metadata", koboUrl("/kobo/$token/v1/library/{Ids}/metadata"))
        put("image_host", koboUrl("/"))
        put(
          "image_url_template",
          koboUrl(
            "/kobo/$token/v1/books/{ImageId}/thumbnail/{Width}/{Height}/false/image.jpg",
          ),
        )
        put(
          "image_url_quality_template",
          koboUrl(
            "/kobo/$token/v1/books/{ImageId}/thumbnail/{Width}/{Height}/{Quality}/{IsGreyscale}/image.jpg",
          ),
        )
      },
    )
  }

private fun ApplicationCall.koboUrl(path: String): String {
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
  val context = request.path().substringBefore("/kobo/", "")
  val normalized = if (path.startsWith("/")) path else "/$path"
  return "${origin.scheme}://${origin.serverHost}$port$context$normalized"
}

private fun wrapper(
  key: String,
  value: JsonElement,
): JsonObject = buildJsonObject { put(key, value) }

private fun zeroAmount(currency: String? = null): JsonObject =
  buildJsonObject {
    currency?.let { put("CurrencyCode", it) }
    put("TotalAmount", 0)
  }

private fun Long.isoInstant(): String = Instant.ofEpochMilli(this).toString()

private fun secureToken(): String {
  val bytes = ByteArray(18)
  SECURE_RANDOM.nextBytes(bytes)
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun KomgaKoboSyncToken.toKoboHeader(): String =
  KOBO_SYNC_TOKEN_PREFIX +
    Base64.getEncoder().withoutPadding().encodeToString(KOBO_JSON.encodeToString(this).encodeToByteArray())

private fun String.toKomgaKoboSyncToken(): KomgaKoboSyncToken {
  if (!startsWith(KOBO_SYNC_TOKEN_PREFIX)) return KomgaKoboSyncToken(rawKoboSyncToken = this)
  return runCatching {
    val decoded = Base64.getDecoder().decode(removePrefix(KOBO_SYNC_TOKEN_PREFIX))
    KOBO_JSON.decodeFromString<KomgaKoboSyncToken>(decoded.decodeToString())
  }.getOrDefault(KomgaKoboSyncToken())
}

private suspend inline fun <reified T> ApplicationCall.respondKobo(value: T) {
  respondText(
    KOBO_JSON.encodeToString(value),
    ContentType.parse("application/json; charset=utf-8"),
  )
}

private const val KOBO_SYNC_TOKEN_PREFIX = "KOMGA."
private const val KOBO_SYNC_TOKEN_HEADER = "x-kobo-synctoken"
private const val KOBO_SYNC_HEADER = "x-kobo-sync"
private const val KOBO_DUMMY_ID = "00000000-0000-0000-0000-000000000001"
private const val KOBO_STREAM_BUFFER_SIZE = 64 * 1_024
private const val DEFAULT_KOBO_SYNC_ITEM_LIMIT = 100
private val SECURE_RANDOM = SecureRandom()
private val KOBO_JSON =
  Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
  }
