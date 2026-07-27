package io.xoboro.server.persistence

import io.xoboro.core.domain.MediaItemFingerprintAlgorithm
import io.xoboro.core.domain.MediaItemFingerprintIndex
import io.xoboro.core.domain.MediaItemId

class JooqMediaItemFingerprintIndex(
  private val database: XoboroDatabase,
) : MediaItemFingerprintIndex {
  override fun findAll(
    algorithm: MediaItemFingerprintAlgorithm,
    fingerprint: String,
  ): List<MediaItemId> {
    require(fingerprint.isNotBlank()) { "Media fingerprint must not be blank" }
    val column =
      when (algorithm) {
        MediaItemFingerprintAlgorithm.KOREADER_PARTIAL_MD5 -> "file_hash_koreader"
      }
    return database.dsl
      .fetch(
        "SELECT id FROM book WHERE $column = ? ORDER BY id",
        fingerprint,
      )
      .map { record ->
        MediaItemId(
          requireNotNull(record.get("id", String::class.java)) {
            "Database field 'id' must not be null"
          },
        )
      }
  }
}
