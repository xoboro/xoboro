package io.xoboro.core.domain

enum class MediaItemFingerprintAlgorithm {
  KOREADER_PARTIAL_MD5,
}

/**
 * Indexes source-content fingerprints by canonical [MediaItemId].
 *
 * Protocol adapters resolve the returned IDs through their own catalog view,
 * so Comic, Novel, Book, Video, and Audio can share this boundary.
 */
fun interface MediaItemFingerprintIndex {
  fun findAll(
    algorithm: MediaItemFingerprintAlgorithm,
    fingerprint: String,
  ): List<MediaItemId>
}
