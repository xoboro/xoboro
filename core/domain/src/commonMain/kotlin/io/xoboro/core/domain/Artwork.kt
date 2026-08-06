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
  /**
   * The name of the file this was read from, for a [ArtworkType.SIDECAR]; null otherwise.
   *
   * A name rather than a path. A sidecar is found again through the same source access that found it
   * first, which is given the owning library's root and the owner's source item id - so the name is
   * the only part not already known, and storing it keeps every filesystem path inside the access that
   * validates against the library root. A row that carried an absolute path would move that decision
   * into the database.
   *
   * It is what makes reducing a sidecar to display size safe: the stored bytes are a grid-sized
   * derivative, and this says where the full-size original still is. Generated artwork has no source
   * file and an upload's only copy is the stored one, so neither carries a name.
   */
  val sourceName: String? = null,
) {
  init {
    require(mediaType.isNotBlank()) { "Artwork media type must not be blank" }
    require(sourceName == null || sourceName.isNotBlank()) {
      "Artwork source name must not be blank when present"
    }
    require(sourceName == null || type == ArtworkType.SIDECAR) {
      "Only sidecar artwork is read from a source file"
    }
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
