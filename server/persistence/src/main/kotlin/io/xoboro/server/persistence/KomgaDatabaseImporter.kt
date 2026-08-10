package io.xoboro.server.persistence

import java.net.URI
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.jooq.DSLContext

data class KomgaImportReport(
  val sourceVersion: String,
  val libraries: Int,
  val users: Int,
  val series: Int,
  val books: Int,
  val readProgresses: Int,
  val collections: Int,
  val readLists: Int,
  val artwork: Int,
  val skippedExternalArtwork: Int,
) {
  fun render(heading: String = "Komga import complete"): String =
    listOfNotNull(
      heading,
      "sourceVersion=$sourceVersion",
      "libraries=$libraries users=$users series=$series books=$books",
      "readProgresses=$readProgresses collections=$collections readLists=$readLists",
      "artwork=$artwork skippedExternalArtwork=$skippedExternalArtwork",
      recoveryAdvice(),
    ).joinToString("\n")

  /**
   * What to do about the artwork this import could not carry over.
   *
   * Komga stores a generated thumbnail as a blob and a sidecar as a `URL` with a null blob, so those
   * rows hold a pointer to a file rather than an image. They are counted and skipped rather than
   * imported: taking them would mean an artwork row with no bytes, which would cost `content NOT NULL`
   * and `file_size > 0` for every row in the table to accommodate an import that has a better answer
   * available.
   *
   * The better answer is that the files those rows point at are still on disk, and a local-artwork
   * refresh reads them directly - producing a display-sized cover and recording its name, which is
   * more than the imported pointer would have given. So the count is not a loss to accept but a step
   * to run, and saying which step is the difference between the two.
   */
  private fun recoveryAdvice(): String? {
    if (skippedExternalArtwork == 0) return null
    return "note: $skippedExternalArtwork sidecar pointer(s) were not imported; " +
      "run a metadata refresh per library once the server is up to read those files from disk"
  }
}

class KomgaDatabaseImporter(
  private val target: XoboroDatabase,
) {
  fun inspect(sourcePath: Path): KomgaImportReport =
    openSource(sourcePath).use { source ->
      validateSource(source)
      source.report()
    }

  fun import(
    sourcePath: Path,
    replaceExisting: Boolean = false,
  ): KomgaImportReport =
    openSource(sourcePath).use { source ->
      validateSource(source)
      val report = source.report()
      target.transaction { destination ->
        requireReplaceableTarget(destination, replaceExisting)
        if (replaceExisting) destination.clearImportedState()
        destination.importLibraries(source)
        destination.importUsers(source)
        destination.importCatalog(source)
        destination.importMetadata(source)
        destination.importMedia(source)
        destination.importOrganizations(source)
        destination.importProgress(source)
        destination.importArtwork(source)
        destination.importPageHashes(source)
        destination.importHistory(source)
        destination.importApiKeys(source)
        destination.importAuthenticationActivity(source)
        destination.importClientSettings(source)
        destination.rebuildCatalogSearch()
      }
      report
    }

  private fun openSource(sourcePath: Path): Connection {
    val normalized = sourcePath.toAbsolutePath().normalize()
    require(Files.isRegularFile(normalized)) { "Komga database does not exist: $normalized" }
    val connection =
      DriverManager.getConnection("jdbc:sqlite:${normalized.toUri()}?mode=ro")
    try {
      connection.createStatement().use { statement ->
        statement.execute("PRAGMA query_only = ON")
        statement.execute("PRAGMA foreign_keys = ON")
      }
      connection.autoCommit = false
      return connection
    } catch (failure: Throwable) {
      connection.close()
      throw failure
    }
  }

  private fun validateSource(source: Connection) {
    val tables =
      source.queryStrings(
        "SELECT upper(name) FROM sqlite_master WHERE type = 'table'",
      ).toSet()
    val missingTables = REQUIRED_TABLES - tables
    require(missingTables.isEmpty()) {
      "Komga database is missing required tables: ${missingTables.sorted().joinToString()}"
    }
    REQUIRED_COLUMNS.forEach { (table, required) ->
      val actual =
        source.queryStrings("SELECT upper(name) FROM pragma_table_info('$table')").toSet()
      val missing = required - actual
      require(missing.isEmpty()) {
        "Komga table $table is missing columns: ${missing.sorted().joinToString()}"
      }
    }
    require(source.schemaVersion() == SUPPORTED_SCHEMA_VERSION) {
      "Unsupported Komga schema ${source.schemaVersion()}; expected $SUPPORTED_SCHEMA_VERSION"
    }
    val integrity =
      source.createStatement().use { statement ->
        statement.executeQuery("PRAGMA quick_check").use { result ->
          buildList {
            while (result.next()) add(result.getString(1))
          }
        }
      }
    require(integrity == listOf("ok")) {
      "Komga database integrity check failed: ${integrity.joinToString()}"
    }
  }

  private fun Connection.report(): KomgaImportReport =
    KomgaImportReport(
      sourceVersion = schemaVersion(),
      libraries = count("LIBRARY"),
      users = count("\"USER\""),
      series = count("SERIES"),
      books = count("BOOK"),
      readProgresses = count("READ_PROGRESS"),
      collections = count("COLLECTION"),
      readLists = count("READLIST"),
      artwork =
        countWhere("THUMBNAIL_BOOK", "THUMBNAIL IS NOT NULL") +
          countWhere("THUMBNAIL_SERIES", "THUMBNAIL IS NOT NULL") +
          countWhere("THUMBNAIL_COLLECTION", "THUMBNAIL IS NOT NULL") +
          countWhere("THUMBNAIL_READLIST", "THUMBNAIL IS NOT NULL"),
      skippedExternalArtwork =
        countWhere("THUMBNAIL_BOOK", "THUMBNAIL IS NULL AND URL IS NOT NULL") +
          countWhere("THUMBNAIL_SERIES", "THUMBNAIL IS NULL AND URL IS NOT NULL"),
    )

  private fun Connection.schemaVersion(): String =
    createStatement().use { statement ->
      statement
        .executeQuery(
          """
          SELECT version
          FROM flyway_schema_history
          WHERE success = 1
          ORDER BY installed_rank DESC
          LIMIT 1
          """.trimIndent(),
        ).use { result ->
          require(result.next()) { "Komga Flyway schema history is empty" }
          result.getString(1)
        }
    }

  private fun requireReplaceableTarget(
    destination: DSLContext,
    replaceExisting: Boolean,
  ) {
    val populated =
      TARGET_ROOT_TABLES.any { table ->
        destination.fetchCount(destination.selectOne().from(table)) > 0
      }
    require(!populated || replaceExisting) {
      "Xoboro database already contains data; pass --replace to overwrite it"
    }
  }

  private fun DSLContext.clearImportedState() {
    execute("DELETE FROM authentication_activity")
    execute("DELETE FROM historical_event")
    execute("DELETE FROM page_hash_known")
    execute("DELETE FROM series_collection")
    execute("DELETE FROM read_list")
    execute("DELETE FROM library")
    execute("DELETE FROM user_account")
    execute("DELETE FROM client_setting_global")
  }

  private fun DSLContext.importLibraries(source: Connection) {
    source.each(
      """
      SELECT *
      FROM LIBRARY
      ORDER BY ID
      """.trimIndent(),
    ) { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      execute(
        """
        INSERT INTO library (
          id, name, root_uri, source_id, created_at_ms, updated_at_ms,
          import_comic_info_book, import_comic_info_series, import_comic_info_collection,
          import_comic_info_read_list, import_comic_info_series_append_volume,
          import_epub_book, import_epub_series, import_mylar_series, import_local_artwork,
          import_barcode_isbn, scan_force_modified_time, scan_on_startup, scan_interval,
          scan_cbx, scan_pdf, scan_epub, repair_extensions, convert_to_cbz,
          empty_trash_after_scan, series_cover, hash_files, hash_pages, hash_koreader,
          analyze_dimensions, oneshots_directory, unavailable_at_ms
        ) VALUES (
          ?, ?, ?, 'local', ?, ?,
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """.trimIndent(),
        row.string("ID"),
        row.string("NAME"),
        row.string("ROOT"),
        created,
        updated,
        row.booleanInt("IMPORT_COMICINFO_BOOK"),
        row.booleanInt("IMPORT_COMICINFO_SERIES"),
        row.booleanInt("IMPORT_COMICINFO_COLLECTION"),
        row.booleanInt("IMPORT_COMICINFO_READLIST"),
        row.booleanInt("IMPORT_COMICINFO_SERIES_APPEND_VOLUME"),
        row.booleanInt("IMPORT_EPUB_BOOK"),
        row.booleanInt("IMPORT_EPUB_SERIES"),
        row.booleanInt("IMPORT_MYLAR_SERIES"),
        row.booleanInt("IMPORT_LOCAL_ARTWORK"),
        row.booleanInt("IMPORT_BARCODE_ISBN"),
        row.booleanInt("SCAN_FORCE_MODIFIED_TIME"),
        row.booleanInt("SCAN_STARTUP"),
        row.string("SCAN_INTERVAL"),
        row.booleanInt("SCAN_CBX"),
        row.booleanInt("SCAN_PDF"),
        row.booleanInt("SCAN_EPUB"),
        row.booleanInt("REPAIR_EXTENSIONS"),
        row.booleanInt("CONVERT_TO_CBZ"),
        row.booleanInt("EMPTY_TRASH_AFTER_SCAN"),
        row.string("SERIES_COVER"),
        row.booleanInt("HASH_FILES"),
        row.booleanInt("HASH_PAGES"),
        row.booleanInt("HASH_KOREADER"),
        row.booleanInt("ANALYZE_DIMENSIONS"),
        row.nullableString("ONESHOTS_DIRECTORY"),
        row.nullableTimestampMillis("UNAVAILABLE_DATE"),
      )
    }
    source.each("SELECT LIBRARY_ID, EXCLUSION FROM LIBRARY_EXCLUSIONS") { row ->
      execute(
        "INSERT INTO library_scan_exclusion (library_id, exclusion) VALUES (?, ?)",
        row.string("LIBRARY_ID"),
        row.string("EXCLUSION"),
      )
    }
  }

  private fun DSLContext.importUsers(source: Connection) {
    source.each("""SELECT * FROM "USER" ORDER BY ID""") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      val ageRestriction = row.nullableInt("AGE_RESTRICTION")
      execute(
        """
        INSERT INTO user_account (
          id, email, password_hash, shares_all_libraries,
          age_restriction, age_restriction_mode, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("EMAIL"),
        row.string("PASSWORD"),
        row.booleanInt("SHARED_ALL_LIBRARIES"),
        ageRestriction,
        ageRestriction?.let {
          if (row.boolean("AGE_RESTRICTION_ALLOW_ONLY")) "ALLOW_ONLY" else "EXCLUDE"
        },
        created,
        updated,
      )
    }
    source.each("SELECT USER_ID, ROLE FROM USER_ROLE ORDER BY USER_ID, ROLE") { row ->
      val role = row.string("ROLE")
      if (role in XOBORO_USER_ROLES) {
        execute(
          "INSERT INTO user_role (user_id, role) VALUES (?, ?)",
          row.string("USER_ID"),
          role,
        )
      }
    }
    source.each(
      "SELECT USER_ID, LIBRARY_ID FROM USER_LIBRARY_SHARING ORDER BY USER_ID, LIBRARY_ID",
    ) { row ->
      execute(
        "INSERT INTO user_library_sharing (user_id, library_id) VALUES (?, ?)",
        row.string("USER_ID"),
        row.string("LIBRARY_ID"),
      )
    }
    // Normalisation collapses labels that differed only in case, so a user who carried both a
    // grant and a denial for one label now hits the conflict clause. min() keeps the denial
    // whichever row arrives first; resolving it by row order instead would have handed such a
    // user the grant, because Komga's raw labels sort by case before they sort by ALLOW.
    source.each(
      """
      SELECT USER_ID, LABEL, ALLOW
      FROM USER_SHARING
      ORDER BY USER_ID, lower(trim(LABEL)), ALLOW
      """.trimIndent(),
    ) { row ->
      val label = normalizedSharingLabel(row.string("LABEL"))
      if (label.isEmpty()) return@each
      execute(
        """
        INSERT INTO user_sharing_label (user_id, label, allow)
        VALUES (?, ?, ?)
        ON CONFLICT (user_id, label) DO UPDATE
          SET allow = min(user_sharing_label.allow, excluded.allow)
        """.trimIndent(),
        row.string("USER_ID"),
        label,
        row.booleanInt("ALLOW"),
      )
    }
  }

  private fun DSLContext.importCatalog(source: Connection) {
    val roots =
      source.associate(
        "SELECT ID, ROOT FROM LIBRARY",
        key = { it.string("ID") },
        value = { it.string("ROOT") },
      )
    source.each(
      """
      SELECT s.*, sm.TITLE_SORT
      FROM SERIES s
      LEFT JOIN SERIES_METADATA sm ON sm.SERIES_ID = s.ID
      ORDER BY s.ID
      """.trimIndent(),
    ) { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      val root = requireNotNull(roots[row.string("LIBRARY_ID")])
      val sourceItemId = row.string("URL")
      execute(
        """
        INSERT INTO series (
          id, library_id, relative_uri, source_item_id, name, sort_title,
          file_modified_ms, book_count, deleted_at_ms, oneshot,
          created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("LIBRARY_ID"),
        relativePath(root, sourceItemId),
        sourceItemId,
        row.string("NAME"),
        row.nullableString("TITLE_SORT") ?: row.string("NAME"),
        row.timestampMillis("FILE_LAST_MODIFIED"),
        row.int("BOOK_COUNT"),
        row.nullableTimestampMillis("DELETED_DATE"),
        row.booleanInt("ONESHOT"),
        created,
        updated,
      )
    }
    copyBatched(
      source = source,
      sourceSql =
      """
      SELECT b.*, m.MEDIA_TYPE
      FROM BOOK b
      LEFT JOIN MEDIA m ON m.BOOK_ID = b.ID
      ORDER BY b.ID
      """.trimIndent(),
      targetSql =
        """
        INSERT INTO book (
          id, library_id, series_id, relative_uri, source_item_id, source_identity,
          name, media_kind, media_item_type, file_size, file_modified_ms,
          file_hash, file_hash_koreader, number, deleted_at_ms, oneshot,
          created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ) { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      val root = requireNotNull(roots[row.string("LIBRARY_ID")])
      val sourceItemId = row.string("URL")
      val mediaKind = mediaKind(sourceItemId, row.nullableString("MEDIA_TYPE"))
      arrayOf(
        row.string("ID"),
        row.string("LIBRARY_ID"),
        row.string("SERIES_ID"),
        relativePath(root, sourceItemId),
        sourceItemId,
        row.string("NAME"),
        mediaKind,
        mediaItemType(mediaKind),
        row.long("FILE_SIZE"),
        row.timestampMillis("FILE_LAST_MODIFIED"),
        row.string("FILE_HASH"),
        row.string("FILE_HASH_KOREADER"),
        row.int("NUMBER"),
        row.nullableTimestampMillis("DELETED_DATE"),
        row.booleanInt("ONESHOT"),
        created,
        updated,
      )
    }
  }

  private fun DSLContext.importMetadata(source: Connection) {
    source.each("SELECT * FROM SERIES_METADATA ORDER BY SERIES_ID") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      execute(
        """
        UPDATE series_metadata SET
          status = ?, title = ?, title_sort = ?, summary = ?, reading_direction = ?,
          publisher = ?, age_rating = ?, language = ?, total_book_count = ?,
          status_lock = ?, title_lock = ?, title_sort_lock = ?, summary_lock = ?,
          reading_direction_lock = ?, publisher_lock = ?, age_rating_lock = ?,
          language_lock = ?, genres_lock = ?, tags_lock = ?, total_book_count_lock = ?,
          sharing_labels_lock = ?, links_lock = ?, alternate_titles_lock = ?,
          created_at_ms = ?, updated_at_ms = ?
        WHERE series_id = ?
        """.trimIndent(),
        row.string("STATUS"),
        row.string("TITLE"),
        row.string("TITLE_SORT"),
        row.string("SUMMARY"),
        row.nullableString("READING_DIRECTION"),
        row.string("PUBLISHER"),
        row.nullableInt("AGE_RATING"),
        row.string("LANGUAGE"),
        row.nullableInt("TOTAL_BOOK_COUNT"),
        row.booleanInt("STATUS_LOCK"),
        row.booleanInt("TITLE_LOCK"),
        row.booleanInt("TITLE_SORT_LOCK"),
        row.booleanInt("SUMMARY_LOCK"),
        row.booleanInt("READING_DIRECTION_LOCK"),
        row.booleanInt("PUBLISHER_LOCK"),
        row.booleanInt("AGE_RATING_LOCK"),
        row.booleanInt("LANGUAGE_LOCK"),
        row.booleanInt("GENRES_LOCK"),
        row.booleanInt("TAGS_LOCK"),
        row.booleanInt("TOTAL_BOOK_COUNT_LOCK"),
        row.booleanInt("SHARING_LABELS_LOCK"),
        row.booleanInt("LINKS_LOCK"),
        row.booleanInt("ALTERNATE_TITLES_LOCK"),
        created,
        updated,
        row.string("SERIES_ID"),
      )
    }
    copyTextRelation(
      source,
      "SELECT SERIES_ID, GENRE FROM SERIES_METADATA_GENRE",
      "INSERT INTO series_metadata_genre (series_id, genre) VALUES (?, ?)",
      "SERIES_ID",
      "GENRE",
    )
    copyTextRelation(
      source,
      "SELECT SERIES_ID, TAG FROM SERIES_METADATA_TAG",
      "INSERT INTO series_metadata_tag (series_id, tag) VALUES (?, ?)",
      "SERIES_ID",
      "TAG",
    )
    source.each("SELECT SERIES_ID, LABEL FROM SERIES_METADATA_SHARING") { row ->
      val label = normalizedSharingLabel(row.string("LABEL"))
      if (label.isEmpty()) return@each
      execute(
        """
        INSERT INTO series_metadata_sharing_label (series_id, sharing_label)
        VALUES (?, ?)
        ON CONFLICT (series_id, sharing_label) DO NOTHING
        """.trimIndent(),
        row.string("SERIES_ID"),
        label,
      )
    }
    source.each(
      """
      SELECT SERIES_ID, LABEL, URL,
        row_number() OVER (PARTITION BY SERIES_ID ORDER BY rowid) - 1 AS ORDINAL
      FROM SERIES_METADATA_LINK
      """.trimIndent(),
    ) { row ->
      execute(
        """
        INSERT INTO series_metadata_link (series_id, ordinal, label, url)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
        row.string("SERIES_ID"),
        row.int("ORDINAL"),
        row.string("LABEL"),
        row.string("URL"),
      )
    }
    source.each(
      """
      SELECT SERIES_ID, LABEL, TITLE,
        row_number() OVER (PARTITION BY SERIES_ID ORDER BY rowid) - 1 AS ORDINAL
      FROM SERIES_METADATA_ALTERNATE_TITLE
      """.trimIndent(),
    ) { row ->
      execute(
        """
        INSERT INTO series_metadata_alternate_title (series_id, ordinal, label, title)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
        row.string("SERIES_ID"),
        row.int("ORDINAL"),
        row.string("LABEL"),
        row.string("TITLE"),
      )
    }

    copyBatched(
      source = source,
      sourceSql = "SELECT * FROM BOOK_METADATA ORDER BY BOOK_ID",
      targetSql =
        """
        UPDATE book_metadata SET
          title = ?, summary = ?, number = ?, number_sort = ?, release_date = ?, isbn = ?,
          title_lock = ?, summary_lock = ?, number_lock = ?, number_sort_lock = ?,
          release_date_lock = ?, authors_lock = ?, tags_lock = ?, isbn_lock = ?,
          links_lock = ?, created_at_ms = ?, updated_at_ms = ?
        WHERE book_id = ?
        """.trimIndent(),
    ) { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      arrayOf(
        row.string("TITLE"),
        row.string("SUMMARY"),
        row.string("NUMBER"),
        row.double("NUMBER_SORT"),
        row.nullableString("RELEASE_DATE"),
        row.string("ISBN"),
        row.booleanInt("TITLE_LOCK"),
        row.booleanInt("SUMMARY_LOCK"),
        row.booleanInt("NUMBER_LOCK"),
        row.booleanInt("NUMBER_SORT_LOCK"),
        row.booleanInt("RELEASE_DATE_LOCK"),
        row.booleanInt("AUTHORS_LOCK"),
        row.booleanInt("TAGS_LOCK"),
        row.booleanInt("ISBN_LOCK"),
        row.booleanInt("LINKS_LOCK"),
        created,
        updated,
        row.string("BOOK_ID"),
      )
    }
    copyBatched(
      source = source,
      sourceSql =
      """
      SELECT BOOK_ID, NAME, ROLE,
        row_number() OVER (PARTITION BY BOOK_ID ORDER BY rowid) - 1 AS ORDINAL
      FROM BOOK_METADATA_AUTHOR
      """.trimIndent(),
      targetSql =
        """
        INSERT INTO book_metadata_author (book_id, ordinal, name, role)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
    ) { row ->
      arrayOf(
        row.string("BOOK_ID"),
        row.int("ORDINAL"),
        row.string("NAME"),
        row.string("ROLE"),
      )
    }
    copyTextRelation(
      source,
      "SELECT BOOK_ID, TAG FROM BOOK_METADATA_TAG",
      "INSERT INTO book_metadata_tag (book_id, tag) VALUES (?, ?)",
      "BOOK_ID",
      "TAG",
    )
    copyBatched(
      source = source,
      sourceSql =
      """
      SELECT BOOK_ID, LABEL, URL,
        row_number() OVER (PARTITION BY BOOK_ID ORDER BY rowid) - 1 AS ORDINAL
      FROM BOOK_METADATA_LINK
      """.trimIndent(),
      targetSql =
        """
        INSERT INTO book_metadata_link (book_id, ordinal, label, url)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
    ) { row ->
      arrayOf(
        row.string("BOOK_ID"),
        row.int("ORDINAL"),
        row.string("LABEL"),
        row.string("URL"),
      )
    }
  }

  private fun DSLContext.copyTextRelation(
    source: Connection,
    sourceSql: String,
    targetSql: String,
    ownerColumn: String,
    valueColumn: String,
  ) {
    copyBatched(source, sourceSql, targetSql) { row ->
      arrayOf(row.string(ownerColumn), row.string(valueColumn))
    }
  }

  private fun DSLContext.importMedia(source: Connection) {
    copyBatched(
      source = source,
      sourceSql = "SELECT * FROM MEDIA ORDER BY BOOK_ID",
      targetSql =
        """
        INSERT INTO media (
          book_id, status, media_type, profile, page_count, comment,
          created_at_ms, updated_at_ms, epub_divina_compatible,
          epub_is_kepub, epub_is_fixed_layout
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """.trimIndent(),
    ) { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      val mediaType = row.nullableString("MEDIA_TYPE")
      arrayOf(
        row.string("BOOK_ID"),
        normalizeMediaStatus(row.string("STATUS")),
        mediaType,
        mediaProfile(mediaType),
        row.int("PAGE_COUNT"),
        row.nullableString("COMMENT"),
        created,
        updated,
        row.booleanInt("EPUB_DIVINA_COMPATIBLE"),
        row.booleanInt("EPUB_IS_KEPUB"),
      )
    }
    copyBatched(
      source = source,
      sourceSql =
      """
      SELECT *
      FROM MEDIA_PAGE
      ORDER BY BOOK_ID, NUMBER
      """.trimIndent(),
      targetSql =
        """
        INSERT INTO book_page (
          book_id, number, file_name, media_type, file_size, width, height, file_hash
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      batchSize = 1_000,
    ) { row ->
      val width = row.nullableInt("WIDTH")
      val height = row.nullableInt("HEIGHT")
      arrayOf(
        row.string("BOOK_ID"),
        row.int("NUMBER") + 1,
        row.string("FILE_NAME"),
        row.string("MEDIA_TYPE"),
        row.nullableLong("FILE_SIZE"),
        width.takeIf { height != null && it != null && it > 0 && height > 0 },
        height.takeIf { width != null && it != null && width > 0 && it > 0 },
        row.string("FILE_HASH"),
      )
    }
    copyBatched(
      source = source,
      sourceSql =
      """
      SELECT BOOK_ID, FILE_NAME, MEDIA_TYPE, FILE_SIZE, SUB_TYPE,
        row_number() OVER (PARTITION BY BOOK_ID ORDER BY rowid) AS NUMBER
      FROM MEDIA_FILE
      ORDER BY BOOK_ID, NUMBER
      """.trimIndent(),
      targetSql =
        """
        INSERT INTO media_file (
          book_id, number, file_name, media_type, file_size, kind
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      batchSize = 1_000,
    ) { row ->
      arrayOf(
        row.string("BOOK_ID"),
        row.int("NUMBER"),
        row.string("FILE_NAME"),
        row.nullableString("MEDIA_TYPE"),
        row.nullableLong("FILE_SIZE"),
        when (row.nullableString("SUB_TYPE")?.uppercase(Locale.ROOT)) {
          "PAGE", "EPUB_PAGE" -> "EPUB_PAGE"
          "ASSET", "EPUB_ASSET" -> "EPUB_ASSET"
          else -> "GENERAL"
        },
      )
    }
  }

  private fun DSLContext.importOrganizations(source: Connection) {
    source.each("SELECT * FROM COLLECTION ORDER BY ID") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      execute(
        """
        INSERT INTO series_collection (
          id, name, ordered, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("NAME"),
        row.booleanInt("ORDERED"),
        created,
        maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE")),
      )
    }
    source.each(
      """
      SELECT COLLECTION_ID, SERIES_ID, NUMBER
      FROM COLLECTION_SERIES
      ORDER BY COLLECTION_ID, NUMBER, SERIES_ID
      """.trimIndent(),
    ) { row ->
      execute(
        """
        INSERT INTO series_collection_member (collection_id, series_id, position)
        VALUES (?, ?, ?)
        """.trimIndent(),
        row.string("COLLECTION_ID"),
        row.string("SERIES_ID"),
        row.int("NUMBER"),
      )
    }
    source.each("SELECT * FROM READLIST ORDER BY ID") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      execute(
        """
        INSERT INTO read_list (
          id, name, summary, ordered, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("NAME"),
        row.string("SUMMARY"),
        row.booleanInt("ORDERED"),
        created,
        maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE")),
      )
    }
    source.each(
      """
      SELECT READLIST_ID, BOOK_ID, NUMBER
      FROM READLIST_BOOK
      ORDER BY READLIST_ID, NUMBER, BOOK_ID
      """.trimIndent(),
    ) { row ->
      execute(
        """
        INSERT INTO read_list_member (read_list_id, book_id, position)
        VALUES (?, ?, ?)
        """.trimIndent(),
        row.string("READLIST_ID"),
        row.string("BOOK_ID"),
        row.int("NUMBER"),
      )
    }
  }

  private fun DSLContext.importProgress(source: Connection) {
    source.each("SELECT * FROM READ_PROGRESS ORDER BY BOOK_ID, USER_ID") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      val updated = maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE"))
      execute(
        """
        INSERT INTO read_progress (
          book_id, user_id, page, completed, read_at_ms,
          device_id, device_name, locator_json, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("BOOK_ID"),
        row.string("USER_ID"),
        row.int("PAGE").coerceAtLeast(0),
        row.booleanInt("COMPLETED"),
        row.nullableTimestampMillis("READ_DATE") ?: updated,
        row.nullableString("DEVICE_ID").orEmpty(),
        row.nullableString("DEVICE_NAME").orEmpty(),
        row.nullableJsonGzip("LOCATOR"),
        created,
        updated,
      )
    }
    source.each("SELECT * FROM READ_PROGRESS_SERIES ORDER BY SERIES_ID, USER_ID") { row ->
      val updated =
        row.nullableTimestampMillis("LAST_MODIFIED_DATE")
          ?: row.nullableTimestampMillis("MOST_RECENT_READ_DATE")
          ?: 0L
      execute(
        """
        INSERT INTO read_progress_series (
          series_id, user_id, books_read_count, books_in_progress_count,
          last_read_at_ms, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("SERIES_ID"),
        row.string("USER_ID"),
        row.int("READ_COUNT").coerceAtLeast(0),
        row.int("IN_PROGRESS_COUNT").coerceAtLeast(0),
        row.nullableTimestampMillis("MOST_RECENT_READ_DATE") ?: updated,
        updated,
        updated,
      )
    }
  }

  private fun DSLContext.importArtwork(source: Connection) {
    importArtworkTable(
      source = source,
      sourceTable = "THUMBNAIL_BOOK",
      ownerColumn = "BOOK_ID",
      ownerKind = "MEDIA_ITEM",
    )
    importArtworkTable(
      source = source,
      sourceTable = "THUMBNAIL_SERIES",
      ownerColumn = "SERIES_ID",
      ownerKind = "SERIES",
    )
    importArtworkTable(
      source = source,
      sourceTable = "THUMBNAIL_COLLECTION",
      ownerColumn = "COLLECTION_ID",
      ownerKind = "COLLECTION",
    )
    importArtworkTable(
      source = source,
      sourceTable = "THUMBNAIL_READLIST",
      ownerColumn = "READLIST_ID",
      ownerKind = "READ_LIST",
    )
  }

  private fun DSLContext.importArtworkTable(
    source: Connection,
    sourceTable: String,
    ownerColumn: String,
    ownerKind: String,
  ) {
    copyBatched(
      source = source,
      sourceSql =
      """
      SELECT *
      FROM $sourceTable
      WHERE THUMBNAIL IS NOT NULL
      ORDER BY $ownerColumn, ID
      """.trimIndent(),
      targetSql =
        """
        INSERT INTO artwork_thumbnail (
          id, owner_kind, owner_id, artwork_type, selected,
          media_type, file_size, width, height, content,
          created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      batchSize = 32,
    ) { row ->
      val content = row.bytes("THUMBNAIL")
      val width = row.int("WIDTH")
      val height = row.int("HEIGHT")
      val mediaType = row.string("MEDIA_TYPE")
      require(content.isNotEmpty() && width > 0 && height > 0 && mediaType.isNotBlank()) {
        "Komga artwork ${row.string("ID")} has incomplete binary metadata"
      }
      val created = row.timestampMillis("CREATED_DATE")
      arrayOf(
        row.string("ID"),
        ownerKind,
        row.string(ownerColumn),
        row.string("TYPE"),
        row.booleanInt("SELECTED"),
        mediaType,
        content.size.toLong(),
        width,
        height,
        content,
        created,
        maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE")),
      )
    }
  }

  private fun DSLContext.copyBatched(
    source: Connection,
    sourceSql: String,
    targetSql: String,
    batchSize: Int = 500,
    bindings: (ResultSet) -> Array<Any?>,
  ) {
    require(batchSize > 0) { "Batch size must be positive" }
    val pending = ArrayList<Array<Any?>>(batchSize)
    source.each(sourceSql) { row ->
      pending += bindings(row)
      if (pending.size == batchSize) {
        batch(targetSql, *pending.toTypedArray()).execute()
        pending.clear()
      }
    }
    if (pending.isNotEmpty()) batch(targetSql, *pending.toTypedArray()).execute()
  }

  private fun DSLContext.importPageHashes(source: Connection) {
    source.each("SELECT * FROM PAGE_HASH ORDER BY HASH") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      execute(
        """
        INSERT INTO page_hash_known (
          hash, file_size, action, delete_count, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("HASH"),
        row.nullableLong("SIZE"),
        row.string("ACTION"),
        row.int("DELETE_COUNT").coerceAtLeast(0),
        created,
        maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE")),
      )
    }
  }

  private fun DSLContext.importHistory(source: Connection) {
    source.each("SELECT * FROM HISTORICAL_EVENT ORDER BY TIMESTAMP, ID") { row ->
      execute(
        """
        INSERT INTO historical_event (id, type, timestamp_ms, book_id, series_id)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("TYPE"),
        row.timestampMillis("TIMESTAMP"),
        row.nullableString("BOOK_ID"),
        row.nullableString("SERIES_ID"),
      )
    }
    source.each("SELECT * FROM HISTORICAL_EVENT_PROPERTIES ORDER BY ID, KEY") { row ->
      execute(
        """
        INSERT INTO historical_event_property (event_id, key, value)
        VALUES (?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("KEY"),
        row.string("VALUE"),
      )
    }
  }

  private fun DSLContext.importApiKeys(source: Connection) {
    source.each("SELECT * FROM USER_API_KEY ORDER BY ID") { row ->
      val created = row.timestampMillis("CREATED_DATE")
      execute(
        """
        INSERT INTO user_api_key (
          id, user_id, key_hash, comment, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.string("ID"),
        row.string("USER_ID"),
        sha512(row.string("API_KEY")),
        row.string("COMMENT"),
        created,
        maxOf(created, row.timestampMillis("LAST_MODIFIED_DATE")),
      )
    }
  }

  private fun DSLContext.importAuthenticationActivity(source: Connection) {
    source.each("SELECT * FROM AUTHENTICATION_ACTIVITY ORDER BY DATE_TIME, rowid") { row ->
      execute(
        """
        INSERT INTO authentication_activity (
          user_id, email, api_key_id, api_key_comment, ip, user_agent,
          success, error, date_time_ms, source
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        row.nullableString("USER_ID"),
        row.nullableString("EMAIL"),
        row.nullableString("API_KEY_ID"),
        row.nullableString("API_KEY_COMMENT"),
        row.nullableString("IP"),
        row.nullableString("USER_AGENT"),
        row.booleanInt("SUCCESS"),
        row.nullableString("ERROR"),
        row.timestampMillis("DATE_TIME"),
        row.nullableString("SOURCE"),
      )
    }
  }

  private fun DSLContext.importClientSettings(source: Connection) {
    source.each("SELECT * FROM CLIENT_SETTINGS_GLOBAL ORDER BY KEY") { row ->
      execute(
        """
        INSERT INTO client_setting_global (
          setting_key, setting_value, allow_unauthorized
        ) VALUES (?, ?, ?)
        """.trimIndent(),
        row.string("KEY"),
        row.string("VALUE"),
        row.booleanInt("ALLOW_UNAUTHORIZED"),
      )
    }
    source.each("SELECT * FROM CLIENT_SETTINGS_USER ORDER BY USER_ID, KEY") { row ->
      execute(
        """
        INSERT INTO client_setting_user (user_id, setting_key, setting_value)
        VALUES (?, ?, ?)
        """.trimIndent(),
        row.string("USER_ID"),
        row.string("KEY"),
        row.string("VALUE"),
      )
    }
    source.each("SELECT * FROM ANNOUNCEMENTS_READ ORDER BY USER_ID, ANNOUNCEMENT_ID") { row ->
      execute(
        """
        INSERT INTO user_announcement_read (user_id, announcement_id)
        VALUES (?, ?)
        """.trimIndent(),
        row.string("USER_ID"),
        row.string("ANNOUNCEMENT_ID"),
      )
    }
  }

  /**
   * Rebuilds the word index after the import has written every metadata row it is going to.
   *
   * The rowid each row lands on is not free to choose: V36 addresses an index row by the key
   * `catalog_search_key` holds for its entity, and everything that later updates or deletes one looks
   * it up there. A rebuild that let SQLite allocate rowids would leave rows nothing could find again.
   */
  private fun DSLContext.rebuildCatalogSearch() {
    // `WHERE true` closes the select off rather than filtering it. Without a `WHERE`, SQLite reads the
    // following `ON` as the start of a join constraint and the upsert clause fails to parse.
    execute(
      """
      INSERT INTO catalog_search_key (entity_type, entity_id)
      SELECT 'BOOK', id FROM book WHERE true
      ON CONFLICT (entity_type, entity_id) DO NOTHING
      """.trimIndent(),
    )
    execute(
      """
      INSERT INTO catalog_search_key (entity_type, entity_id)
      SELECT 'SERIES', id FROM series WHERE true
      ON CONFLICT (entity_type, entity_id) DO NOTHING
      """.trimIndent(),
    )
    execute("DELETE FROM catalog_search_fts")
    execute(
      """
      INSERT INTO catalog_search_fts (
        rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
      )
      SELECT
        search_key.index_rowid, 'BOOK', source.entity_id, source.title, source.summary,
        source.contributors, source.labels, source.identifiers
      FROM catalog_book_search_source source
      JOIN catalog_search_key search_key
        ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
      """.trimIndent(),
    )
    execute(
      """
      INSERT INTO catalog_search_fts (
        rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
      )
      SELECT
        search_key.index_rowid, 'SERIES', source.entity_id, source.title, source.summary,
        source.contributors, source.labels, source.identifiers
      FROM catalog_series_search_source source
      JOIN catalog_search_key search_key
        ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
      """.trimIndent(),
    )
  }

  private companion object {
    const val SUPPORTED_SCHEMA_VERSION = "20250730173126"
    val TARGET_ROOT_TABLES =
      listOf(
        "library",
        "user_account",
        "series_collection",
        "read_list",
        "historical_event",
        "page_hash_known",
        "client_setting_global",
        "authentication_activity",
      )
    val REQUIRED_TABLES =
      setOf(
        "FLYWAY_SCHEMA_HISTORY",
        "LIBRARY",
        "SERIES",
        "SERIES_METADATA",
        "BOOK",
        "BOOK_METADATA",
        "MEDIA",
        "MEDIA_PAGE",
        "MEDIA_FILE",
        "USER",
        "USER_ROLE",
        "USER_LIBRARY_SHARING",
        "USER_SHARING",
        "READ_PROGRESS",
        "COLLECTION",
        "COLLECTION_SERIES",
        "READLIST",
        "READLIST_BOOK",
        "THUMBNAIL_BOOK",
        "THUMBNAIL_SERIES",
        "THUMBNAIL_COLLECTION",
        "THUMBNAIL_READLIST",
        "PAGE_HASH",
        "HISTORICAL_EVENT",
        "HISTORICAL_EVENT_PROPERTIES",
        "USER_API_KEY",
        "AUTHENTICATION_ACTIVITY",
        "CLIENT_SETTINGS_GLOBAL",
        "CLIENT_SETTINGS_USER",
        "ANNOUNCEMENTS_READ",
      )
    val REQUIRED_COLUMNS =
      mapOf(
        "LIBRARY" to setOf("ID", "ROOT", "SCAN_INTERVAL", "HASH_KOREADER"),
        "SERIES" to setOf("ID", "URL", "LIBRARY_ID", "ONESHOT"),
        "BOOK" to setOf("ID", "URL", "SERIES_ID", "FILE_HASH_KOREADER"),
        "USER" to setOf("ID", "EMAIL", "PASSWORD"),
        "READ_PROGRESS" to setOf("BOOK_ID", "USER_ID", "PAGE", "LOCATOR"),
      )
    val XOBORO_USER_ROLES =
      setOf("ADMIN", "FILE_DOWNLOAD", "PAGE_STREAMING", "KOBO_SYNC", "KOREADER_SYNC")
  }
}

private fun Connection.count(table: String): Int =
  createStatement().use { statement ->
    statement.executeQuery("SELECT count(*) FROM $table").use { result ->
      check(result.next())
      result.getInt(1)
    }
  }

private fun Connection.countWhere(
  table: String,
  condition: String,
): Int =
  createStatement().use { statement ->
    statement.executeQuery("SELECT count(*) FROM $table WHERE $condition").use { result ->
      check(result.next())
      result.getInt(1)
    }
  }

private fun Connection.queryStrings(sql: String): List<String> =
  createStatement().use { statement ->
    statement.executeQuery(sql).use { result ->
      buildList {
        while (result.next()) add(result.getString(1))
      }
    }
  }

private inline fun Connection.each(
  sql: String,
  block: (ResultSet) -> Unit,
) {
  createStatement().use { statement ->
    statement.executeQuery(sql).use { result ->
      while (result.next()) block(result)
    }
  }
}

private inline fun <K, V> Connection.associate(
  sql: String,
  key: (ResultSet) -> K,
  value: (ResultSet) -> V,
): Map<K, V> =
  buildMap {
    each(sql) { row -> put(key(row), value(row)) }
  }

private fun ResultSet.string(column: String): String =
  requireNotNull(getString(column)) { "Komga column $column must not be null" }

private fun ResultSet.nullableString(column: String): String? = getString(column)

private fun ResultSet.int(column: String): Int =
  getInt(column).also {
    require(!wasNull()) { "Komga column $column must not be null" }
  }

private fun ResultSet.nullableInt(column: String): Int? =
  getInt(column).let { if (wasNull()) null else it }

private fun ResultSet.long(column: String): Long =
  getLong(column).also {
    require(!wasNull()) { "Komga column $column must not be null" }
  }

private fun ResultSet.nullableLong(column: String): Long? =
  getLong(column).let { if (wasNull()) null else it }

private fun ResultSet.double(column: String): Double =
  getDouble(column).also {
    require(!wasNull()) { "Komga column $column must not be null" }
  }

private fun ResultSet.boolean(column: String): Boolean =
  when (val value = int(column)) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("Komga column $column must be boolean, got $value")
  }

private fun ResultSet.booleanInt(column: String): Int = if (boolean(column)) 1 else 0

private fun ResultSet.bytes(column: String): ByteArray =
  requireNotNull(getBytes(column)) { "Komga column $column must not be null" }

private fun ResultSet.nullableJsonGzip(column: String): String? =
  when (val value = getObject(column)) {
    null -> null
    is ByteArray ->
      try {
        if (value.size >= 2 && value[0] == 0x1f.toByte() && value[1] == 0x8b.toByte()) {
          GZIPInputStream(ByteArrayInputStream(value)).use { input ->
            input.readNBytes(MAXIMUM_LOCATOR_BYTES + 1)
              .takeIf { it.size <= MAXIMUM_LOCATOR_BYTES }
              ?.toString(StandardCharsets.UTF_8)
          }
        } else {
          value.toString(StandardCharsets.UTF_8)
        }
      } catch (_: Exception) {
        null
      }
    is String -> value
    else -> value.toString()
  }

private fun ResultSet.timestampMillis(column: String): Long =
  requireNotNull(nullableTimestampMillis(column)) {
    "Komga column $column must not be null"
  }

private fun ResultSet.nullableTimestampMillis(column: String): Long? {
  val value = getObject(column) ?: return null
  return timestampMillis(value)
}

private fun timestampMillis(value: Any): Long =
  when (value) {
    is Number -> {
      val number = value.toLong()
      if (number in 1..99_999_999_999L) number * 1_000 else number
    }
    else -> {
      val text = value.toString().trim()
      require(text.isNotEmpty()) { "Komga timestamp must not be blank" }
      TIMESTAMP_PARSERS.firstNotNullOfOrNull { parser ->
        runCatching { parser(text) }.getOrNull()
      } ?: throw IllegalArgumentException("Invalid Komga timestamp: $text")
    }
  }.also { require(it >= 0) { "Komga timestamp must not be negative" } }

private val TIMESTAMP_PARSERS: List<(String) -> Long> =
  listOf(
    { Instant.parse(it).toEpochMilli() },
    { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
    {
      LocalDateTime
        .parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()
    },
    {
      LocalDateTime
        .parse(it, KOMGA_SQLITE_TIMESTAMP)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()
    },
    { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
  )

private val KOMGA_SQLITE_TIMESTAMP: DateTimeFormatter =
  DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .toFormatter()

private fun relativePath(
  root: String,
  item: String,
): String {
  val rootUri = URI(root)
  val itemUri = URI(item)
  require(rootUri.scheme.equals(itemUri.scheme, ignoreCase = true)) {
    "Komga item URL scheme differs from its library root"
  }
  val rootPath = Path.of(rootUri).toAbsolutePath().normalize()
  val itemPath = Path.of(itemUri).toAbsolutePath().normalize()
  require(itemPath.startsWith(rootPath)) { "Komga item is outside its library root: $item" }
  val relative = rootPath.relativize(itemPath).joinToString("/") { it.toString() }
  return relative.ifBlank { "." }
}

private fun mediaKind(
  item: String,
  mediaType: String?,
): String =
  when {
    mediaType.equals("application/epub+zip", ignoreCase = true) ||
      item.substringBefore('?').endsWith(".epub", ignoreCase = true) -> "EPUB"
    mediaType.equals("application/pdf", ignoreCase = true) ||
      item.substringBefore('?').endsWith(".pdf", ignoreCase = true) -> "PDF"
    else -> "COMIC_ARCHIVE"
  }

private fun mediaItemType(mediaKind: String): String =
  when (mediaKind) {
    "EPUB" -> "NOVEL"
    "PDF" -> "BOOK"
    else -> "COMIC"
  }

private fun mediaProfile(mediaType: String?): String? =
  when {
    mediaType.equals("application/epub+zip", ignoreCase = true) -> "EPUB"
    mediaType.equals("application/pdf", ignoreCase = true) -> "PDF"
    mediaType == null -> null
    else -> "DIVINA"
  }

private fun normalizeMediaStatus(status: String): String =
  status.uppercase(Locale.ROOT).takeIf {
    it in setOf("UNKNOWN", "ERROR", "READY", "UNSUPPORTED", "OUTDATED")
  } ?: "UNKNOWN"

/**
 * Lower-cases a sharing label to the form the restriction filter compares against.
 *
 * A user's labels are lower-cased when they are read back, and the series column carries no NOCASE
 * collation, so the stored side has to be lower-cased on the way in. This is the same invariant
 * [JooqSeriesMetadataRepository] maintains for labels the server writes itself. Copying Komga's
 * labels verbatim let a user who had been denied "RestrictedLabel" read a series carrying that
 * label, because the denial read back as "restrictedlabel" and matched nothing.
 */
private fun normalizedSharingLabel(label: String): String = label.trim().lowercase(Locale.ROOT)

private fun sha512(value: String): String =
  MessageDigest
    .getInstance("SHA-512")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private const val MAXIMUM_LOCATOR_BYTES = 1024 * 1024
