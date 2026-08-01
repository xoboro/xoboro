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
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.MediaItemFingerprintAlgorithm
import io.xoboro.core.domain.MediaItemFingerprintIndex
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.ReadProgress
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
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
  private val catalog: CatalogReadRepository,
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

  /**
   * Resolves a KOReader document fingerprint to the single media item it identifies for this caller.
   *
   * The lookup goes through [CatalogReadRepository] with the caller's [catalogAccess] rather than
   * reading the book repository directly. A fingerprint is supplied by the client and is not a
   * capability: matching one must not reveal an item the caller cannot otherwise see. Filtering only
   * on [io.xoboro.core.domain.User.canAccessLibrary] — as this did before — left age-rating and
   * sharing-label restrictions unenforced, so a restricted caller could read and overwrite progress
   * for an item the catalog hides from them.
   *
   * An item the caller cannot see is treated as absent, not as a conflict: when two items share a
   * fingerprint and only one is visible, the visible one resolves. Restricted content must not
   * change the outcome for a caller who is not allowed to know it exists.
   */
  private fun resolve(
    fingerprint: String,
    user: User,
  ): ResolvedFingerprint? {
    if (fingerprint.isBlank()) return null
    val access = user.catalogAccess()
    val matches =
      fingerprints
        .findAll(MediaItemFingerprintAlgorithm.KOREADER_PARTIAL_MD5, fingerprint)
        .mapNotNull { catalog.findBookByIdOrNull(it, access) }
        .map(CatalogBook::book)
        .filter { it.deletedAtMillis == null }
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

/**
 * The percentage KOReader is told, in `0..1`.
 *
 * Prefers the stored locator, and falls back to the page for progress written by a client that
 * keeps no locator — the Komga REST `read-progress` patch, which records only a page. The
 * fallback is `(page - 1) / pageCount`, the *start* of that page, because it has to agree with
 * the locator branch above it: both answer the same field, and one field carrying two
 * conventions is the failure this whole change exists to remove. Page 1 of anything is therefore
 * 0, not `1 / pageCount`.
 */
private fun ReadProgress.totalProgression(media: BookMedia): Float =
  locatorJson
    ?.let { runCatching { KOREADER_JSON.decodeFromString<R2LocatorDto>(it) }.getOrNull() }
    ?.locations
    ?.totalProgression
    ?: if (media.pageCount == 0) {
      0F
    } else {
      ((page - 1).toFloat() / media.pageCount.toFloat()).coerceIn(0F, 1F)
    }

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

/**
 * The one-based page a KOReader client stores for [position].
 *
 * Positions divide the publication into [positions]`.size` equal slots, so position `k` begins
 * inside page slot `floor((k - 1) * pageCount / n)`, one-based. Position 1 therefore always maps
 * to page 1, and the last position maps to [pageCount] whenever `pageCount <= n` — which holds
 * for every reflowable EPUB, because page count sums `ceil(compressedSize / POSITION_BYTES)`
 * while positions chunk on uncompressed size.
 *
 * This reads [MediaPosition.position] rather than inverting [MediaPosition.totalProgression],
 * which is what the previous version did. Two reasons, and the second is the one that matters:
 * the position index is the stored source of truth while `totalProgression` is derived from it,
 * and inverting the derived `Float` is not reliable at the last position. `(n - 1) / n` is not
 * representable in `Float` for most `n`, so `pageCount * (n - 1) / n` lands just above or just
 * below the integer depending on `n`, and `floor` of the low case silently costs a reader the
 * final page. Integer arithmetic on the index cannot do that. It also decouples the two: the
 * locator convention can change again without moving anybody's stored page.
 */
private fun BookMedia.pageFor(position: MediaPosition): Int {
  val pages = pageCount.coerceAtLeast(1)
  val slots = positions.size.coerceAtLeast(1)
  return (((position.position - 1).toLong() * pages / slots).toInt() + 1).coerceIn(1, pages)
}

private class KoreaderMediaItemNotFoundException : IllegalStateException()

private class KoreaderFingerprintConflictException : IllegalStateException()

private val KOREADER_JSON =
  Json {
    explicitNulls = false
    ignoreUnknownKeys = true
  }
private val DOC_FRAGMENT = Regex("""DocFragment\[(\d+)]""", RegexOption.IGNORE_CASE)
private val LEGACY_DOC_FRAGMENT = Regex("""#_doc_fragment_(\d+)_""", RegexOption.IGNORE_CASE)
