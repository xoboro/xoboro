package io.xoboro.core.application

data class FontResource(
  val fileName: String,
  val mediaType: String,
  val bytes: ByteArray,
)

interface FontResourceCatalog {
  fun families(): Set<String>

  fun resource(
    family: String,
    fileName: String,
  ): FontResource?

  fun css(family: String): String?
}

data class ServerRelease(
  val version: String,
  val releaseDate: String,
  val url: String,
  val latest: Boolean,
  val preRelease: Boolean,
  val description: String,
)

fun interface ServerReleaseCatalog {
  suspend fun releases(): List<ServerRelease>
}

interface CompatibilityMaintenanceRequester : DuplicatePageRemovalRequester {
  fun regenerateBookArtwork(forBiggerResultOnly: Boolean): Int
}
