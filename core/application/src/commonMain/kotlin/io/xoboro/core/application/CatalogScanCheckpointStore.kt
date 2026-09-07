package io.xoboro.core.application

import io.xoboro.core.domain.Library

/** Persists the last complete local inventory fingerprint for unchanged-scan detection. */
interface CatalogScanCheckpointStore {
  fun exists(library: Library): Boolean

  fun matches(
    library: Library,
    sourceFingerprint: String,
  ): Boolean

  fun replace(
    library: Library,
    sourceFingerprint: String,
    completedAtMillis: Long,
  )
}
