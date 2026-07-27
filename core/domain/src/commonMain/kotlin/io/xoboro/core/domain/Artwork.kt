package io.xoboro.core.domain

data class ArtworkId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Artwork ID must not be blank" }
  }
}

enum class ArtworkOwnerKind {
  MEDIA_ITEM,
  SERIES,
  COLLECTION,
  READ_LIST,
}

data class ArtworkOwner(
  val kind: ArtworkOwnerKind,
  val id: String,
) {
  init {
    require(id.isNotBlank()) { "Artwork owner ID must not be blank" }
  }
}

enum class ArtworkType {
  GENERATED,
  SIDECAR,
  USER_UPLOADED,
}

data class Artwork(
  val id: ArtworkId,
  val owner: ArtworkOwner,
  val type: ArtworkType,
  val selected: Boolean,
  val mediaType: String,
  val fileSize: Long,
  val width: Int,
  val height: Int,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(mediaType.isNotBlank()) { "Artwork media type must not be blank" }
    require(fileSize > 0) { "Artwork file size must be positive" }
    require(width > 0 && height > 0) { "Artwork dimensions must be positive" }
    require(createdAtMillis >= 0) { "Artwork creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Artwork update timestamp must not precede creation"
    }
  }
}

data class ArtworkContent(
  val artwork: Artwork,
  val bytes: ByteArray,
) {
  init {
    require(bytes.isNotEmpty()) { "Artwork content must not be empty" }
    require(bytes.size.toLong() == artwork.fileSize) {
      "Artwork content size must match its metadata"
    }
  }
}

interface ArtworkRepository {
  fun findAll(owner: ArtworkOwner): List<Artwork>

  fun findByIdOrNull(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Artwork?

  fun findSelectedOrNull(owner: ArtworkOwner): Artwork?

  fun content(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): ByteArray?

  fun insert(content: ArtworkContent)

  fun replaceGenerated(content: ArtworkContent)

  fun replaceSidecars(
    owner: ArtworkOwner,
    contents: List<ArtworkContent>,
  )

  fun markSelected(
    owner: ArtworkOwner,
    id: ArtworkId,
    updatedAtMillis: Long,
  ): Boolean

  fun delete(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean
}
