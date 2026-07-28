package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.MediaItemFingerprintAlgorithm
import io.xoboro.core.domain.MediaItemFingerprintIndex
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.komgaKoreaderSyncRoutes(sync: KoreaderSyncLifecycle) {
  post("/koreader/users/create") {
    call.respond(HttpStatusCode.Forbidden, "User creation is disabled")
  }
  authenticate(KOMGA_KOREADER_AUTHENTICATION) {
    route("/koreader") {
      get("/users/auth") {
        call.respond(KoreaderUserAuthenticationDto())
      }
      get("/syncs/progress/{bookHash}") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        when (
          val result =
            sync.find(
              fingerprint = requireNotNull(call.parameters["bookHash"]),
              user = principal.user,
            )
        ) {
          KoreaderProgressResult.NotFound -> call.respond(HttpStatusCode.NotFound)
          KoreaderProgressResult.Conflict -> call.respond(HttpStatusCode.Conflict)
          KoreaderProgressResult.NoProgress -> call.respond(HttpStatusCode.OK)
          is KoreaderProgressResult.Found -> call.respond(result.progress)
        }
      }
      put("/syncs/progress") {
        val principal = requireNotNull(call.principal<KomgaPrincipal>())
        val request = call.receive<KoreaderDocumentProgressDto>()
        try {
          sync.update(request, principal.user)
          call.respond(HttpStatusCode.NoContent)
        } catch (_: KoreaderMediaItemNotFoundException) {
          call.respond(HttpStatusCode.NotFound)
        } catch (_: KoreaderFingerprintConflictException) {
          call.respond(HttpStatusCode.Conflict)
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

class KoreaderSyncLifecycle(
  private val fingerprints: MediaItemFingerprintIndex,
  private val books: BookRepository,
  private val media: BookMediaRepository,
  private val progress: ReadProgressLifecycle,
  private val currentTimeMillis: () -> Long,
) {
  fun find(
    fingerprint: String,
    user: User,
  ): KoreaderProgressResult {
    val item = resolve(fingerprint, user) ?: return KoreaderProgressResult.NotFound
    if (item.conflict) return KoreaderProgressResult.Conflict
    val book = requireNotNull(item.book)
    val analyzed = media.findByBookIdOrNull(book.id) ?: return KoreaderProgressResult.NotFound
    if (analyzed.profile == null) return KoreaderProgressResult.NotFound
    val saved = progress.findBook(book.id, user.id) ?: return KoreaderProgressResult.NoProgress
    return KoreaderProgressResult.Found(
      KoreaderDocumentProgressDto(
        document = fingerprint,
        percentage = saved.totalProgression(analyzed),
        progress = saved.toKoreaderPosition(analyzed),
        device = saved.deviceName,
        deviceId = saved.deviceId,
      ),
    )
  }

  fun update(
    request: KoreaderDocumentProgressDto,
    user: User,
  ) {
    require(request.percentage in 0F..1F) { "Progress percentage must be between zero and one" }
    val item = resolve(request.document, user) ?: throw KoreaderMediaItemNotFoundException()
    if (item.conflict) throw KoreaderFingerprintConflictException()
    val book = requireNotNull(item.book)
    val analyzed =
      media.findByBookIdOrNull(book.id) ?: throw KoreaderMediaItemNotFoundException()
    val mapped = request.toLocator(analyzed)
    val now =
      currentTimeMillis().also {
        require(it >= 0) { "Progression timestamp must not be negative" }
      }
    progress.updateBookProgression(
      bookId = book.id,
      userId = user.id,
      page = mapped.page,
      modifiedAtMillis = now,
      deviceId = request.deviceId,
      deviceName = request.device,
      locatorJson = KOREADER_JSON.encodeToString(mapped.locator),
    )
  }

  private fun resolve(
    fingerprint: String,
    user: User,
  ): ResolvedFingerprint? {
    if (fingerprint.isBlank()) return null
    val matches =
      fingerprints
        .findAll(MediaItemFingerprintAlgorithm.KOREADER_PARTIAL_MD5, fingerprint)
        .mapNotNull(books::findByIdOrNull)
        .filter { it.deletedAtMillis == null && user.canAccessLibrary(it.libraryId) }
    return when (matches.size) {
      0 -> null
      1 -> ResolvedFingerprint(book = matches.single())
      else -> ResolvedFingerprint(conflict = true)
    }
  }

  private data class ResolvedFingerprint(
    val book: io.xoboro.core.domain.Book? = null,
    val conflict: Boolean = false,
  )
}

sealed interface KoreaderProgressResult {
  data object NotFound : KoreaderProgressResult

  data object Conflict : KoreaderProgressResult

  data object NoProgress : KoreaderProgressResult

  data class Found(
    val progress: KoreaderDocumentProgressDto,
  ) : KoreaderProgressResult
}

@Serializable
data class KoreaderUserAuthenticationDto(
  val authorized: String = "OK",
)

@Serializable
data class KoreaderDocumentProgressDto(
  val document: String,
  val percentage: Float,
  val progress: String,
  val device: String,
  @SerialName("device_id")
  val deviceId: String,
)

private data class MappedKoreaderLocator(
  val page: Int,
  val locator: R2LocatorDto,
)

private fun ReadProgress.totalProgression(media: BookMedia): Float =
  locatorJson
    ?.let { runCatching { KOREADER_JSON.decodeFromString<R2LocatorDto>(it) }.getOrNull() }
    ?.locations
    ?.totalProgression
    ?: if (media.pageCount == 0) 0F else page.toFloat() / media.pageCount.toFloat()

private fun ReadProgress.toKoreaderPosition(media: BookMedia): String =
  when (media.profile) {
    MediaProfile.DIVINA, MediaProfile.PDF -> page.toString()
    MediaProfile.EPUB -> {
      val locator =
        locatorJson
          ?.let { runCatching { KOREADER_JSON.decodeFromString<R2LocatorDto>(it) }.getOrNull() }
      val resourceIndex = media.resourceHrefs().indexOf(locator?.href)
      "/body/DocFragment[${resourceIndex + 1}].0"
    }
    null -> throw KoreaderMediaItemNotFoundException()
  }

private fun KoreaderDocumentProgressDto.toLocator(media: BookMedia): MappedKoreaderLocator =
  when (media.profile) {
    MediaProfile.DIVINA, MediaProfile.PDF -> {
      val page = progress.toIntOrNull() ?: throw IllegalArgumentException("Invalid page progress")
      MappedKoreaderLocator(
        page = page,
        locator =
          R2LocatorDto(
            href = "",
            type = "",
            locations =
              R2LocationDto(
                position = page,
                totalProgression = percentage,
              ),
          ),
      )
    }
    MediaProfile.EPUB -> {
      val resourceIndex =
        DOC_FRAGMENT.find(progress)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
          ?: LEGACY_DOC_FRAGMENT.find(progress)?.groupValues?.get(1)?.toIntOrNull()
          ?: throw IllegalArgumentException("Could not get Epub resource index from progress")
      val href =
        media.resourceHrefs().getOrNull(resourceIndex)
          ?: throw IllegalArgumentException("Epub resource index is outside the manifest")
      val position =
        media.positions.firstOrNull { it.href == href }
          ?: throw IllegalArgumentException("Epub resource has no analyzed position")
      MappedKoreaderLocator(
        page = media.pageFor(position),
        locator =
          R2LocatorDto(
            href = href,
            type = position.mediaType,
            locations =
              R2LocationDto(
                progression = 0F,
                totalProgression = position.totalProgression,
              ),
          ),
      )
    }
    null -> throw KoreaderMediaItemNotFoundException()
  }

private fun BookMedia.resourceHrefs(): List<String> =
  positions.map(MediaPosition::href).distinct()

private fun BookMedia.pageFor(position: MediaPosition): Int =
  (pageCount * position.totalProgression).roundToInt().coerceIn(1, pageCount.coerceAtLeast(1))

private class KoreaderMediaItemNotFoundException : IllegalStateException()

private class KoreaderFingerprintConflictException : IllegalStateException()

private val KOREADER_JSON =
  Json {
    explicitNulls = false
    ignoreUnknownKeys = true
  }
private val DOC_FRAGMENT = Regex("""DocFragment\[(\d+)]""", RegexOption.IGNORE_CASE)
private val LEGACY_DOC_FRAGMENT = Regex("""#_doc_fragment_(\d+)_""", RegexOption.IGNORE_CASE)
