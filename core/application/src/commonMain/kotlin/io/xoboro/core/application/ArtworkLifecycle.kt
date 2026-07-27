package io.xoboro.core.application

import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkContent
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkRepository
import io.xoboro.core.domain.ArtworkType

data class ProcessedArtwork(
  val bytes: ByteArray,
  val mediaType: String,
  val width: Int,
  val height: Int,
)

fun interface ArtworkProcessor {
  fun process(input: ByteArray): ProcessedArtwork
}

class ArtworkLifecycle(
  private val artwork: ArtworkRepository,
  private val processor: ArtworkProcessor,
  private val idFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) {
  fun findAll(owner: ArtworkOwner): List<Artwork> = artwork.findAll(owner)

  fun selectedContentOrNull(owner: ArtworkOwner): ArtworkContent? =
    artwork.findSelectedOrNull(owner)?.let { item ->
      artwork.content(owner, item.id)?.let { ArtworkContent(item, it) }
    }

  fun contentOrNull(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): ArtworkContent? =
    artwork.findByIdOrNull(owner, id)?.let { item ->
      artwork.content(owner, id)?.let { ArtworkContent(item, it) }
    }

  fun addUploaded(
    owner: ArtworkOwner,
    input: ByteArray,
    selected: Boolean,
  ): Artwork {
    require(input.isNotEmpty()) { "Uploaded artwork must not be empty" }
    require(input.size <= MAXIMUM_UPLOAD_BYTES) { "Uploaded artwork exceeds the size limit" }
    val processed = processor.process(input)
    require(processed.bytes.isNotEmpty()) { "Processed artwork must not be empty" }
    val now = now()
    val item =
      Artwork(
        id = ArtworkId(idFactory()),
        owner = owner,
        type = ArtworkType.USER_UPLOADED,
        selected = selected,
        mediaType = processed.mediaType,
        fileSize = processed.bytes.size.toLong(),
        width = processed.width,
        height = processed.height,
        createdAtMillis = now,
      )
    artwork.insert(ArtworkContent(item, processed.bytes))
    return requireNotNull(artwork.findByIdOrNull(owner, item.id))
  }

  fun markSelected(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean = artwork.markSelected(owner, id, now())

  fun deleteUploaded(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean {
    val item = artwork.findByIdOrNull(owner, id) ?: return false
    require(item.type == ArtworkType.USER_UPLOADED) { "Only uploaded artwork can be deleted" }
    return artwork.delete(owner, id)
  }

  private fun now(): Long =
    currentTimeMillis().also { require(it >= 0) { "Artwork timestamp must not be negative" } }

  companion object {
    const val MAXIMUM_UPLOAD_BYTES: Int = 20 * 1_024 * 1_024
  }
}
