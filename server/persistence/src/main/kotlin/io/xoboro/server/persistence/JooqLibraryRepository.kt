package io.xoboro.server.persistence

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SeriesCover
import io.xoboro.core.domain.SourceLocation
import org.jooq.DSLContext
import org.jooq.Record

class JooqLibraryRepository(
  private val database: XoboroDatabase,
) : LibraryRepository {
  override fun findById(id: LibraryId): Library =
    findByIdOrNull(id) ?: throw NoSuchElementException("Library not found: ${id.value}")

  override fun findByIdOrNull(id: LibraryId): Library? =
    database.dsl
      .fetchOne("$SELECT_LIBRARY WHERE id = ?", id.value)
      ?.let { record ->
        record.toLibrary(loadExclusions(listOf(id))[id].orEmpty())
      }

  override fun findAll(): List<Library> {
    val records = database.dsl.fetch("$SELECT_LIBRARY ORDER BY name COLLATE NOCASE, id")
    return records.toLibraries()
  }

  override fun findAllByIds(ids: Collection<LibraryId>): List<Library> {
    if (ids.isEmpty()) return emptyList()

    val values = ids.distinct().map(LibraryId::value)
    val placeholders = values.joinToString(",") { "?" }
    return database.dsl
      .fetch(
        "$SELECT_LIBRARY WHERE id IN ($placeholders) ORDER BY name COLLATE NOCASE, id",
        *values.toTypedArray(),
      )
      .toLibraries()
  }

  override fun insert(library: Library) {
    database.transaction { transaction ->
      transaction.insertLibrary(library)
      transaction.replaceExclusions(library)
    }
  }

  override fun update(library: Library) {
    database.transaction { transaction ->
      val affected = transaction.updateLibrary(library)
      if (affected == 0) {
        throw NoSuchElementException("Library not found: ${library.id.value}")
      }
      transaction.replaceExclusions(library)
    }
  }

  override fun delete(id: LibraryId) {
    database.dsl.execute("DELETE FROM library WHERE id = ?", id.value)
  }

  override fun deleteAll() {
    database.dsl.execute("DELETE FROM library")
  }

  override fun count(): Long =
    database.dsl
      .fetchOne("SELECT count(*) FROM library")
      ?.get(0)
      ?.let { it as Number }
      ?.toLong()
      ?: 0L

  private fun List<Record>.toLibraries(): List<Library> {
    val exclusions = loadExclusions(map { LibraryId(it.requiredString("id")) })
    return map { record ->
      val id = LibraryId(record.requiredString("id"))
      record.toLibrary(exclusions[id].orEmpty())
    }
  }

  private fun loadExclusions(ids: Collection<LibraryId>): Map<LibraryId, Set<String>> {
    if (ids.isEmpty()) return emptyMap()

    val values = ids.distinct().map(LibraryId::value)
    val placeholders = values.joinToString(",") { "?" }
    return database.dsl
      .fetch(
        """
        SELECT library_id, exclusion
        FROM library_scan_exclusion
        WHERE library_id IN ($placeholders)
        ORDER BY exclusion
        """.trimIndent(),
        *values.toTypedArray(),
      )
      .groupBy(
        keySelector = { LibraryId(it.requiredString("library_id")) },
        valueTransform = { it.requiredString("exclusion") },
      )
      .mapValues { (_, valuesForLibrary) -> valuesForLibrary.toSet() }
  }

  private fun DSLContext.insertLibrary(library: Library) {
    execute(
      """
      INSERT INTO library (
        id, name, root_uri, source_id, created_at_ms, updated_at_ms,
        import_comic_info_book, import_comic_info_series, import_comic_info_collection,
        import_comic_info_read_list, import_comic_info_series_append_volume,
        import_epub_book, import_epub_series, import_pdf_book, import_mylar_series,
        import_local_artwork,
        import_barcode_isbn, scan_force_modified_time, scan_on_startup, scan_interval,
        scan_cbx, scan_pdf, scan_epub, repair_extensions, convert_to_cbz,
        empty_trash_after_scan, series_cover, hash_files, hash_pages, hash_koreader,
        analyze_dimensions, oneshots_directory, unavailable_at_ms
      ) VALUES (
        ?, ?, ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?,
        ?, ?, ?, ?,
        ?,
        ?, ?, ?, ?,
        ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?,
        ?, ?, ?
      )
      """.trimIndent(),
      *library.bindValues(),
    )
  }

  private fun DSLContext.updateLibrary(library: Library): Int =
    execute(
      """
      UPDATE library SET
        name = ?, root_uri = ?, source_id = ?, updated_at_ms = ?,
        import_comic_info_book = ?, import_comic_info_series = ?,
        import_comic_info_collection = ?, import_comic_info_read_list = ?,
        import_comic_info_series_append_volume = ?, import_epub_book = ?,
        import_epub_series = ?, import_pdf_book = ?, import_mylar_series = ?,
        import_local_artwork = ?,
        import_barcode_isbn = ?, scan_force_modified_time = ?, scan_on_startup = ?,
        scan_interval = ?, scan_cbx = ?, scan_pdf = ?, scan_epub = ?,
        repair_extensions = ?, convert_to_cbz = ?, empty_trash_after_scan = ?,
        series_cover = ?, hash_files = ?, hash_pages = ?, hash_koreader = ?,
        analyze_dimensions = ?, oneshots_directory = ?, unavailable_at_ms = ?
      WHERE id = ?
      """.trimIndent(),
      *library.updateBindValues(),
    )

  private fun DSLContext.replaceExclusions(library: Library) {
    execute("DELETE FROM library_scan_exclusion WHERE library_id = ?", library.id.value)
    library.settings.scanDirectoryExclusions.sorted().forEach { exclusion ->
      execute(
        "INSERT INTO library_scan_exclusion (library_id, exclusion) VALUES (?, ?)",
        library.id.value,
        exclusion,
      )
    }
  }

  private fun Library.bindValues(): Array<Any?> =
    arrayOf(
      id.value,
      name,
      root.itemId,
      root.sourceId,
      createdAtMillis,
      updatedAtMillis,
      *settings.bindValues(),
      unavailableAtMillis,
    )

  private fun Library.updateBindValues(): Array<Any?> =
    arrayOf(
      name,
      root.itemId,
      root.sourceId,
      updatedAtMillis,
      *settings.bindValues(),
      unavailableAtMillis,
      id.value,
    )

  private fun LibrarySettings.bindValues(): Array<Any?> =
    arrayOf(
      importComicInfoBook.toSqliteInt(),
      importComicInfoSeries.toSqliteInt(),
      importComicInfoCollection.toSqliteInt(),
      importComicInfoReadList.toSqliteInt(),
      importComicInfoSeriesAppendVolume.toSqliteInt(),
      importEpubBook.toSqliteInt(),
      importEpubSeries.toSqliteInt(),
      importPdfBook.toSqliteInt(),
      importMylarSeries.toSqliteInt(),
      importLocalArtwork.toSqliteInt(),
      importBarcodeIsbn.toSqliteInt(),
      scanForceModifiedTime.toSqliteInt(),
      scanOnStartup.toSqliteInt(),
      scanInterval.name,
      scanCbx.toSqliteInt(),
      scanPdf.toSqliteInt(),
      scanEpub.toSqliteInt(),
      repairExtensions.toSqliteInt(),
      convertToCbz.toSqliteInt(),
      emptyTrashAfterScan.toSqliteInt(),
      seriesCover.name,
      hashFiles.toSqliteInt(),
      hashPages.toSqliteInt(),
      hashKoreader.toSqliteInt(),
      analyzeDimensions.toSqliteInt(),
      oneshotsDirectory,
    )

  private fun Record.toLibrary(exclusions: Set<String>): Library =
    Library(
      id = LibraryId(requiredString("id")),
      name = requiredString("name"),
      root =
        SourceLocation(
          sourceId = requiredString("source_id"),
          itemId = requiredString("root_uri"),
        ),
      settings =
        LibrarySettings(
          importComicInfoBook = requiredBoolean("import_comic_info_book"),
          importComicInfoSeries = requiredBoolean("import_comic_info_series"),
          importComicInfoCollection = requiredBoolean("import_comic_info_collection"),
          importComicInfoReadList = requiredBoolean("import_comic_info_read_list"),
          importComicInfoSeriesAppendVolume =
            requiredBoolean("import_comic_info_series_append_volume"),
          importEpubBook = requiredBoolean("import_epub_book"),
          importPdfBook = requiredBoolean("import_pdf_book"),
          importEpubSeries = requiredBoolean("import_epub_series"),
          importMylarSeries = requiredBoolean("import_mylar_series"),
          importLocalArtwork = requiredBoolean("import_local_artwork"),
          importBarcodeIsbn = requiredBoolean("import_barcode_isbn"),
          scanForceModifiedTime = requiredBoolean("scan_force_modified_time"),
          scanOnStartup = requiredBoolean("scan_on_startup"),
          scanInterval = ScanInterval.valueOf(requiredString("scan_interval")),
          scanCbx = requiredBoolean("scan_cbx"),
          scanPdf = requiredBoolean("scan_pdf"),
          scanEpub = requiredBoolean("scan_epub"),
          scanDirectoryExclusions = exclusions,
          repairExtensions = requiredBoolean("repair_extensions"),
          convertToCbz = requiredBoolean("convert_to_cbz"),
          emptyTrashAfterScan = requiredBoolean("empty_trash_after_scan"),
          seriesCover = SeriesCover.valueOf(requiredString("series_cover")),
          hashFiles = requiredBoolean("hash_files"),
          hashPages = requiredBoolean("hash_pages"),
          hashKoreader = requiredBoolean("hash_koreader"),
          analyzeDimensions = requiredBoolean("analyze_dimensions"),
          oneshotsDirectory = get("oneshots_directory", String::class.java),
        ),
      unavailableAtMillis = nullableLongText("unavailable_at_ms_64"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.nullableLongText(field: String): Long? =
    get(field, String::class.java)?.toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requireNotNull(get(field, Int::class.java))) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be 0 or 1, got $value")
    }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  companion object {
    private const val SELECT_LIBRARY =
      """
      SELECT library.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64,
        CAST(unavailable_at_ms AS TEXT) AS unavailable_at_ms_64
      FROM library
      """
  }
}
