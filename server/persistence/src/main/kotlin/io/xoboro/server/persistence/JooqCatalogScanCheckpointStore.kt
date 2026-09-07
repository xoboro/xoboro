package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogScanCheckpointStore
import io.xoboro.core.domain.Library

class JooqCatalogScanCheckpointStore(
  private val database: XoboroDatabase,
) : CatalogScanCheckpointStore {
  override fun exists(library: Library): Boolean =
    database.dsl.fetchOne(
      "SELECT 1 FROM catalog_scan_checkpoint WHERE library_id = ?",
      library.id.value,
    ) != null

  override fun matches(
    library: Library,
    sourceFingerprint: String,
  ): Boolean {
    require(sourceFingerprint.isNotBlank()) { "Source fingerprint must not be blank" }
    return database.dsl.fetchOne(
      """
      SELECT 1
      FROM catalog_scan_checkpoint
      WHERE library_id = ?
        AND source_id = ?
        AND root_item_id = ?
        AND configuration_key = ?
        AND fingerprint = ?
      """.trimIndent(),
      library.id.value,
      library.root.sourceId,
      library.root.itemId,
      configurationKey(library),
      sourceFingerprint,
    ) != null
  }

  override fun replace(
    library: Library,
    sourceFingerprint: String,
    completedAtMillis: Long,
  ) {
    require(sourceFingerprint.isNotBlank()) { "Source fingerprint must not be blank" }
    require(completedAtMillis >= 0) { "Checkpoint completion timestamp must not be negative" }
    database.dsl.execute(
      """
      INSERT INTO catalog_scan_checkpoint (
        library_id, source_id, root_item_id, configuration_key, fingerprint, completed_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(library_id) DO UPDATE SET
        source_id = excluded.source_id,
        root_item_id = excluded.root_item_id,
        configuration_key = excluded.configuration_key,
        fingerprint = excluded.fingerprint,
        completed_at_ms = excluded.completed_at_ms
      """.trimIndent(),
      library.id.value,
      library.root.sourceId,
      library.root.itemId,
      configurationKey(library),
      sourceFingerprint,
      completedAtMillis,
    )
  }

  /** Only settings that change which candidates reconciliation receives belong here. */
  private fun configurationKey(library: Library): String =
    buildString {
      append(if (library.settings.scanCbx) '1' else '0')
      append(if (library.settings.scanPdf) '1' else '0')
      append(if (library.settings.scanEpub) '1' else '0')
      append(if (library.settings.scanForceModifiedTime) '1' else '0')
      appendToken(library.settings.oneshotsDirectory)
      library.settings.scanDirectoryExclusions.sorted().forEach { exclusion ->
        appendToken(exclusion)
      }
    }

  private fun StringBuilder.appendToken(value: String?) {
    if (value == null) {
      append("-1:")
    } else {
      append(value.length).append(':').append(value)
    }
  }
}
