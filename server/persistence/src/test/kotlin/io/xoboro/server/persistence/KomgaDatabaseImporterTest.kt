package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.application.catalogAccess
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserId
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class KomgaDatabaseImporterTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `imports a synthetic Komga 1_25 catalog atomically and safely`() {
    val sourcePath = temporaryDirectory.resolve("komga.sqlite")
    createSource(sourcePath)
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("xoboro.sqlite"))).use {
        target ->
      val importer = KomgaDatabaseImporter(target)

      val inspected = importer.inspect(sourcePath)
      assertEquals(1, inspected.libraries)
      assertEquals(BOOK_IDS.size, inspected.books)
      assertEquals(0, target.int("SELECT count(*) FROM library"))

      val imported = importer.import(sourcePath)
      assertEquals(inspected, imported)
      assertEquals("Synthetic library", target.string("SELECT name FROM library"))
      assertEquals(
        "series/item.cbz",
        target.string("SELECT relative_uri FROM book WHERE id = 'book-1'"),
      )
      assertEquals(
        "Synthetic chapter",
        target.string("SELECT title FROM book_metadata WHERE book_id = 'book-1'"),
      )
      assertEquals(
        "Primary Creator",
        target.string("SELECT name FROM book_metadata_author WHERE book_id = 'book-1'"),
      )
      assertEquals(
        4,
        target.int(
          "SELECT page FROM read_progress WHERE book_id = 'book-1' AND user_id = 'user-1'",
        ),
      )
      assertEquals(
        SERIES_IDS.size,
        target.int(
          "SELECT count(*) FROM series_collection_member WHERE collection_id = 'collection-1'",
        ),
      )
      assertEquals(
        4,
        target.int("SELECT count(*) FROM read_list_member WHERE read_list_id = 'read-list-1'"),
      )
      assertEquals(1, target.int("SELECT count(*) FROM artwork_thumbnail"))
      assertEquals(1, target.int("SELECT number FROM book_page WHERE book_id = 'book-1'"))
      assertEquals(
        BOOK_IDS.size + SERIES_IDS.size,
        target.int("SELECT count(*) FROM catalog_search_fts"),
      )
      assertEquals(
        "synthetic-password-hash",
        target.string("SELECT password_hash FROM user_account WHERE id = 'user-1'"),
      )
      val importedApiKey = target.string("SELECT key_hash FROM user_api_key")
      assertNotEquals("synthetic-api-key", importedApiKey)
      assertEquals(sha512("synthetic-api-key"), importedApiKey)

      assertFailsWith<IllegalArgumentException> {
        importer.import(sourcePath)
      }
      importer.import(sourcePath, replaceExisting = true)
      assertEquals(BOOK_IDS.size, target.int("SELECT count(*) FROM book"))
      assertEquals(USER_IDS.size, target.int("SELECT count(*) FROM user_account"))
    }

    DriverManager.getConnection("jdbc:sqlite:$sourcePath").use { source ->
      assertEquals(BOOK_IDS.size, source.queryInt("SELECT count(*) FROM BOOK"))
    }
  }

  /**
   * Production acceptance for the four migrated properties whose defects are silent: content
   * restrictions, read progress, collection order and read-list order.
   *
   * Every assertion reads through the path the running server reads through — [JooqUserRepository]
   * projected onto a [io.xoboro.core.application.CatalogAccess] by
   * [io.xoboro.core.application.catalogAccess], then [JooqCatalogReadRepository] — rather than
   * comparing imported rows against the fixture rows. Comparing rows would prove only that a copy
   * happened; it would not prove that a migrated user who was restricted from something is still
   * unable to read it, which is the property that matters.
   *
   * The Komga database is deleted immediately after the import and before any assertion, so
   * everything below is answered by the migrated database alone. That is the operational meaning
   * of "never require runtime DB compatibility".
   */
  @Test
  fun `migrated restrictions progress and manual order hold on the server read path`() {
    val sourcePath = temporaryDirectory.resolve("acceptance-komga.sqlite")
    createSource(sourcePath)
    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("acceptance.sqlite"))).use {
        target ->
      KomgaDatabaseImporter(target).import(sourcePath)
      Files.delete(sourcePath)

      val users = JooqUserRepository(target)
      val catalog = JooqCatalogReadRepository(target)

      fun access(userId: String) =
        requireNotNull(users.findByIdOrNull(UserId(userId))) {
          "Komga user $userId was not migrated"
        }.catalogAccess()

      fun readableSeries(userId: String): List<String> =
        catalog
          .findSeries(
            query = SeriesCatalogQuery(),
            access = access(userId),
            page = CatalogPageRequest(size = 50),
          ).content
          .map { it.series.id.value }
          .sorted()

      // Restrictions. An unrestricted administrator reads all four series, so a denial below is
      // a denial and not an empty catalog. Each restricted user has both a series it must not
      // reach and a series it must still reach, and the four expectations differ from one
      // another, so no single filter outcome satisfies them all.
      assertEquals(SERIES_IDS.sorted(), readableSeries("user-1"))
      assertEquals(
        listOf("series-1", "series-open", "series-teen"),
        readableSeries("user-age-exclude"),
      )
      // ALLOW_ONLY at exactly the restricted age admits it, and admits nothing unrated.
      assertEquals(listOf("series-1", "series-teen"), readableSeries("user-age-allow"))
      assertEquals(
        listOf("series-1", "series-open", "series-teen"),
        readableSeries("user-label-exclude"),
      )
      assertEquals(listOf("series-open"), readableSeries("user-label-allow"))

      // The same restriction has to hold on the book a restricted series contains, because that
      // is the request that would deliver the content itself.
      assertNull(catalog.findBookByIdOrNull(BookId("book-restricted"), access("user-age-exclude")))
      assertNull(catalog.findBookByIdOrNull(BookId("book-restricted"), access("user-label-exclude")))
      assertNull(catalog.findBookByIdOrNull(BookId("book-restricted"), access("user-label-allow")))
      assertNull(catalog.findBookByIdOrNull(BookId("book-open"), access("user-age-allow")))
      assertNotNull(catalog.findBookByIdOrNull(BookId("book-restricted"), access("user-1")))
      assertNotNull(catalog.findBookByIdOrNull(BookId("book-open"), access("user-label-allow")))
      assertNotNull(catalog.findBookByIdOrNull(BookId("book-1"), access("user-label-exclude")))
      assertNull(
        catalog.findSeriesByIdOrNull(SeriesId("series-restricted"), access("user-label-exclude")),
      )

      // Progress. A migrated reader resumes where it stopped, at both boundaries: the first page
      // of a book it has just opened, and the final page of a book it finished.
      val readerAccess = access("user-1")
      val opening =
        requireNotNull(catalog.findBookByIdOrNull(BookId("book-first-position"), readerAccess))
      val closing =
        requireNotNull(catalog.findBookByIdOrNull(BookId("book-last-position"), readerAccess))
      val openingProgress = requireNotNull(opening.readProgress)
      val closingProgress = requireNotNull(closing.readProgress)
      assertEquals(1, openingProgress.page)
      assertFalse(openingProgress.completed)
      assertEquals(requireNotNull(closing.media).pageCount, closingProgress.page)
      assertTrue(closingProgress.completed)
      assertEquals(OPENING_LOCATOR, openingProgress.locatorJson)
      assertEquals(CLOSING_LOCATOR, closingProgress.locatorJson)
      // Progress is per user, not per book: a second migrated user has none on the same books.
      assertNull(
        catalog
          .findBookByIdOrNull(BookId("book-first-position"), access("user-age-exclude"))
          ?.readProgress,
      )
      assertNull(
        catalog
          .findBookByIdOrNull(BookId("book-last-position"), access("user-age-exclude"))
          ?.readProgress,
      )

      // Collections. The expected order agrees with no incidental order available to the query
      // layer: not series id ascending or descending, not title ascending or descending, and not
      // the order the fixture inserted the membership rows in, which is the reverse of it. The
      // second collection holds two of the same series in the opposite relative order, so an
      // import that lost the per-collection number cannot satisfy both.
      val collections = JooqSeriesCollectionRepository(target)
      val orderedCollection = requireNotNull(collections.findByIdOrNull(CollectionId("collection-1")))
      assertEquals(
        listOf("series-restricted", "series-teen", "series-1", "series-open"),
        orderedCollection.seriesIds.map { it.value },
      )
      assertTrue(orderedCollection.ordered)
      val unorderedCollection =
        requireNotNull(collections.findByIdOrNull(CollectionId("collection-2")))
      assertEquals(
        listOf("series-1", "series-restricted"),
        unorderedCollection.seriesIds.map { it.value },
      )
      assertFalse(unorderedCollection.ordered)

      // Read lists, on the same argument. Book number_sort ascending is one more incidental order
      // here, and the expected order disagrees with that too.
      val readLists = JooqReadListRepository(target)
      val orderedReadList = requireNotNull(readLists.findByIdOrNull(ReadListId("read-list-1")))
      assertEquals(
        listOf("book-last-position", "book-restricted", "book-1", "book-first-position"),
        orderedReadList.bookIds.map { it.value },
      )
      assertTrue(orderedReadList.ordered)
      assertEquals("Synthetic list summary", orderedReadList.summary)
      val unorderedReadList = requireNotNull(readLists.findByIdOrNull(ReadListId("read-list-2")))
      assertEquals(
        listOf("book-1", "book-restricted"),
        unorderedReadList.bookIds.map { it.value },
      )
      assertFalse(unorderedReadList.ordered)
    }
  }

  @Test
  fun `rolls back the target when a source relationship cannot be imported`() {
    val sourcePath = temporaryDirectory.resolve("invalid-komga.sqlite")
    createSource(sourcePath)
    DriverManager.getConnection("jdbc:sqlite:$sourcePath").use { source ->
      source.execute("UPDATE BOOK SET SERIES_ID = 'missing-series'")
    }

    XoboroDatabase.open(DatabaseConfig(temporaryDirectory.resolve("target.sqlite"))).use {
        target ->
      assertFailsWith<Exception> {
        KomgaDatabaseImporter(target).import(sourcePath)
      }
      assertEquals(0, target.int("SELECT count(*) FROM library"))
      assertEquals(0, target.int("SELECT count(*) FROM user_account"))
      assertEquals(0, target.int("SELECT count(*) FROM book"))
    }
  }

  private fun createSource(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
      SCHEMA.forEach { ddl ->
        connection.createStatement().use { statement -> statement.execute(ddl) }
      }
      connection.execute(
        """
        INSERT INTO flyway_schema_history(installed_rank, version, success)
        VALUES (1, '20250730173126', 1)
        """.trimIndent(),
      )
      connection.execute(
        """
        INSERT INTO LIBRARY (
          ID, CREATED_DATE, LAST_MODIFIED_DATE, NAME, ROOT,
          IMPORT_COMICINFO_BOOK, IMPORT_COMICINFO_SERIES, IMPORT_COMICINFO_COLLECTION,
          IMPORT_EPUB_BOOK, IMPORT_EPUB_SERIES, SCAN_FORCE_MODIFIED_TIME,
          SCAN_STARTUP, IMPORT_LOCAL_ARTWORK, IMPORT_COMICINFO_READLIST,
          IMPORT_BARCODE_ISBN, CONVERT_TO_CBZ, REPAIR_EXTENSIONS,
          EMPTY_TRASH_AFTER_SCAN, IMPORT_MYLAR_SERIES, SERIES_COVER,
          UNAVAILABLE_DATE, HASH_FILES, HASH_PAGES, ANALYZE_DIMENSIONS,
          IMPORT_COMICINFO_SERIES_APPEND_VOLUME, ONESHOTS_DIRECTORY,
          SCAN_CBX, SCAN_PDF, SCAN_EPUB, SCAN_INTERVAL, HASH_KOREADER
        ) VALUES (
          'library-1', '2026-07-27 10:00:00', '2026-07-27 10:01:00',
          'Synthetic library', 'file:///synthetic/library',
          1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 1,
          'FIRST', NULL, 1, 0, 1, 1, NULL, 1, 1, 1, 'EVERY_6H', 1
        )
        """.trimIndent(),
      )
      connection.execute("INSERT INTO LIBRARY_EXCLUSIONS VALUES ('library-1', 'ignored')")
      connection.execute(
        """
        INSERT INTO "USER" VALUES (
          'user-1', '2026-07-27 10:00:00', '2026-07-27 10:00:00',
          'reader@example.invalid', 'synthetic-password-hash', 1, NULL, NULL
        )
        """.trimIndent(),
      )
      connection.execute("INSERT INTO USER_ROLE VALUES ('user-1', 'ADMIN')")
      connection.insertRestrictedUsers()
      connection.execute(
        """
        INSERT INTO SERIES VALUES (
          'series-1', '2026-07-27 10:00:00', '2026-07-27 10:02:00',
          '2026-07-27 09:00:00', 'Synthetic series',
          'file:///synthetic/library/series', 'library-1', 3, NULL, 0
        )
        """.trimIndent(),
      )
      connection.execute(
        """
        INSERT INTO SERIES_METADATA (
          CREATED_DATE, LAST_MODIFIED_DATE, STATUS, STATUS_LOCK,
          TITLE, TITLE_LOCK, TITLE_SORT, TITLE_SORT_LOCK,
          SUMMARY, SUMMARY_LOCK, READING_DIRECTION, READING_DIRECTION_LOCK,
          PUBLISHER, PUBLISHER_LOCK, AGE_RATING, AGE_RATING_LOCK,
          LANGUAGE, LANGUAGE_LOCK, GENRES_LOCK, TAGS_LOCK,
          TOTAL_BOOK_COUNT, TOTAL_BOOK_COUNT_LOCK, SHARING_LABELS_LOCK,
          LINKS_LOCK, ALTERNATE_TITLES_LOCK, SERIES_ID
        ) VALUES (
          '2026-07-27 10:00:00', '2026-07-27 10:02:00',
          'ONGOING', 0, 'Synthetic series', 0, 'Synthetic series', 0,
          'Synthetic summary', 0, 'LEFT_TO_RIGHT', 0, 'Synthetic publisher', 0,
          10, 0, 'en', 0, 0, 0, 1, 0, 0, 0, 0, 'series-1'
        )
        """.trimIndent(),
      )
      connection.execute("INSERT INTO SERIES_METADATA_GENRE VALUES ('Synthetic genre', 'series-1')")
      connection.execute("INSERT INTO SERIES_METADATA_TAG VALUES ('Synthetic tag', 'series-1')")
      connection.insertRestrictedCatalog()
      connection.execute(
        """
        INSERT INTO BOOK VALUES (
          'book-1', '2026-07-27 10:00:00', '2026-07-27 10:03:00',
          '2026-07-27 09:30:00.123456789', 'Synthetic item',
          'file:///synthetic/library/series/item.cbz', 'series-1', 1234, 1,
          'library-1', 'file-hash', NULL, 0, 'koreader-hash'
        )
        """.trimIndent(),
      )
      connection.execute(
        """
        INSERT INTO BOOK_METADATA VALUES (
          '2026-07-27 10:00:00', '2026-07-27 10:03:00',
          '1', 0, 1.0, 0, '2026-07-27', 0,
          'Synthetic book summary', 0, 'Synthetic chapter', 0,
          0, 0, 'book-1', '9780306406157', 0, 0
        )
        """.trimIndent(),
      )
      connection.execute(
        "INSERT INTO BOOK_METADATA_AUTHOR VALUES ('Primary Creator', 'writer', 'book-1')",
      )
      connection.execute("INSERT INTO BOOK_METADATA_TAG VALUES ('Chapter tag', 'book-1')")
      connection.execute(
        """
        INSERT INTO MEDIA VALUES (
          'application/zip', 'READY', '2026-07-27 10:00:00',
          '2026-07-27 10:03:00', NULL, 'book-1', 5, 0, 0
        )
        """.trimIndent(),
      )
      connection.execute(
        """
        INSERT INTO MEDIA_PAGE VALUES (
          '001.png', 'image/png', 0, 'book-1', 100, 200, 'page-hash', 50
        )
        """.trimIndent(),
      )
      connection.execute(
        """
        INSERT INTO READ_PROGRESS VALUES (
          'book-1', 'user-1', '2026-07-27 10:00:00', '2026-07-27 10:04:00',
          4, 0, '2026-07-27 10:04:00', 'device-1', 'Synthetic device', NULL
        )
        """.trimIndent(),
      )
      connection.execute(
        """
        INSERT INTO READ_PROGRESS_SERIES VALUES (
          'series-1', 'user-1', 0, 1, '2026-07-27 10:04:00',
          '2026-07-27 10:04:00'
        )
        """.trimIndent(),
      )
      connection.insertBoundaryProgress()
      connection.insertManualOrder()
      connection.prepareStatement(
        """
        INSERT INTO THUMBNAIL_BOOK VALUES (
          'artwork-1', ?, NULL, 1, 'USER_UPLOADED',
          '2026-07-27 10:00:00', '2026-07-27 10:00:00',
          'book-1', 1, 1, 'image/png', 1
        )
        """.trimIndent(),
      ).use {
        it.setBytes(1, byteArrayOf(1))
        it.executeUpdate()
      }
      connection.execute(
        """
        INSERT INTO USER_API_KEY VALUES (
          'key-1', 'user-1', '2026-07-27 10:00:00',
          '2026-07-27 10:00:00', 'synthetic-api-key', 'Synthetic client'
        )
        """.trimIndent(),
      )
    }
  }

  /**
   * Four readers, one per restriction shape Komga can store. The sharing labels are mixed case
   * because Komga stores the label an administrator typed, on both the user and the series.
   */
  private fun Connection.insertRestrictedUsers() {
    insertUser("user-age-exclude", ageRestriction = "16", allowOnly = "0")
    insertUser("user-age-allow", ageRestriction = "12", allowOnly = "1")
    insertUser("user-label-exclude")
    insertUser("user-label-allow")
    execute("INSERT INTO USER_SHARING VALUES ('RestrictedLabel', 0, 'user-label-exclude')")
    // The same label again in another case, granted rather than denied. Komga keys these rows
    // case-sensitively so it can hold both; the migrated user must keep the denial. Were the
    // grant to win instead, this reader would see the restricted series and nothing else.
    execute("INSERT INTO USER_SHARING VALUES ('restrictedlabel', 1, 'user-label-exclude')")
    execute("INSERT INTO USER_SHARING VALUES ('OpenLabel', 1, 'user-label-allow')")
  }

  private fun Connection.insertUser(
    id: String,
    ageRestriction: String = "NULL",
    allowOnly: String = "NULL",
  ) {
    execute(
      """
      INSERT INTO "USER" VALUES (
        '$id', '2026-07-27 10:00:00', '2026-07-27 10:00:00',
        '$id@example.invalid', 'synthetic-password-hash', 1, $ageRestriction, $allowOnly
      )
      """.trimIndent(),
    )
    execute("INSERT INTO USER_ROLE VALUES ('$id', 'PAGE_STREAMING')")
  }

  /**
   * Three more series spanning what the restriction filters have to distinguish: rated above the
   * restricted age, rated at exactly the restricted age, and unrated. Each carries one book, so a
   * denial can be observed on the series and on the content it holds.
   */
  private fun Connection.insertRestrictedCatalog() {
    insertSeries("series-restricted", "Synthetic restricted series")
    insertSeriesMetadata("series-restricted", "Synthetic restricted series", ageRating = "18")
    execute("INSERT INTO SERIES_METADATA_SHARING VALUES ('RestrictedLabel', 'series-restricted')")
    insertSeries("series-teen", "Synthetic teen series")
    insertSeriesMetadata("series-teen", "Synthetic teen series", ageRating = "12")
    insertSeries("series-open", "Synthetic open series")
    insertSeriesMetadata("series-open", "Synthetic open series", ageRating = "NULL")
    execute("INSERT INTO SERIES_METADATA_SHARING VALUES ('OpenLabel', 'series-open')")

    insertBook("book-restricted", "series-restricted", "Synthetic restricted item", 4.0, 3)
    insertBook("book-teen", "series-teen", "Synthetic teen item", 1.0, 3)
    insertBook("book-open", "series-open", "Synthetic open item", 1.0, 3)
  }

  /**
   * Progress at both ends of a book: page 1 of a five-page book that was only opened, and page 7
   * of a seven-page book that was finished. The locators differ so that one value cannot answer
   * for both, and are stored gzipped, which is how Komga writes the column.
   */
  private fun Connection.insertBoundaryProgress() {
    insertBook("book-first-position", "series-1", "Synthetic opening item", 2.0, 5)
    insertBook("book-last-position", "series-1", "Synthetic closing item", 3.0, 7)
    insertProgress("book-first-position", page = 1, completed = 0, locator = OPENING_LOCATOR)
    insertProgress("book-last-position", page = 7, completed = 1, locator = CLOSING_LOCATOR)
  }

  private fun Connection.insertProgress(
    bookId: String,
    page: Int,
    completed: Int,
    locator: String,
  ) {
    prepareStatement(
      """
      INSERT INTO READ_PROGRESS VALUES (
        '$bookId', 'user-1', '2026-07-27 10:00:00', '2026-07-27 10:05:00',
        $page, $completed, '2026-07-27 10:05:00', 'device-1', 'Synthetic device', ?
      )
      """.trimIndent(),
    ).use { statement ->
      statement.setBytes(1, gzip(locator))
      statement.executeUpdate()
    }
  }

  /**
   * Manual order for one collection and one read list, plus a second of each holding two of the
   * same members in the opposite relative order. The membership rows are inserted in an order
   * that is not the stored order, so rowid cannot stand in for NUMBER.
   */
  private fun Connection.insertManualOrder() {
    execute(
      """
      INSERT INTO COLLECTION VALUES (
        'collection-1', 'Synthetic collection', 1, 4,
        '2026-07-27 10:00:00', '2026-07-27 10:00:00'
      )
      """.trimIndent(),
    )
    execute(
      """
      INSERT INTO COLLECTION VALUES (
        'collection-2', 'Synthetic alternate collection', 0, 2,
        '2026-07-27 10:00:00', '2026-07-27 10:00:00'
      )
      """.trimIndent(),
    )
    execute("INSERT INTO COLLECTION_SERIES VALUES ('collection-1', 'series-open', 3)")
    execute("INSERT INTO COLLECTION_SERIES VALUES ('collection-1', 'series-1', 2)")
    execute("INSERT INTO COLLECTION_SERIES VALUES ('collection-1', 'series-teen', 1)")
    execute("INSERT INTO COLLECTION_SERIES VALUES ('collection-1', 'series-restricted', 0)")
    execute("INSERT INTO COLLECTION_SERIES VALUES ('collection-2', 'series-1', 0)")
    execute("INSERT INTO COLLECTION_SERIES VALUES ('collection-2', 'series-restricted', 1)")

    execute(
      """
      INSERT INTO READLIST VALUES (
        'read-list-1', 'Synthetic reading order', 4,
        '2026-07-27 10:00:00', '2026-07-27 10:00:00',
        'Synthetic list summary', 1
      )
      """.trimIndent(),
    )
    execute(
      """
      INSERT INTO READLIST VALUES (
        'read-list-2', 'Synthetic alternate reading order', 2,
        '2026-07-27 10:00:00', '2026-07-27 10:00:00',
        'Synthetic alternate list summary', 0
      )
      """.trimIndent(),
    )
    execute("INSERT INTO READLIST_BOOK VALUES ('read-list-1', 'book-1', 2)")
    execute("INSERT INTO READLIST_BOOK VALUES ('read-list-1', 'book-first-position', 3)")
    execute("INSERT INTO READLIST_BOOK VALUES ('read-list-1', 'book-last-position', 0)")
    execute("INSERT INTO READLIST_BOOK VALUES ('read-list-1', 'book-restricted', 1)")
    execute("INSERT INTO READLIST_BOOK VALUES ('read-list-2', 'book-1', 0)")
    execute("INSERT INTO READLIST_BOOK VALUES ('read-list-2', 'book-restricted', 1)")
  }

  private fun Connection.insertSeries(
    id: String,
    name: String,
  ) {
    execute(
      """
      INSERT INTO SERIES VALUES (
        '$id', '2026-07-27 10:00:00', '2026-07-27 10:02:00',
        '2026-07-27 09:00:00', '$name',
        'file:///synthetic/library/$id', 'library-1', 1, NULL, 0
      )
      """.trimIndent(),
    )
  }

  private fun Connection.insertSeriesMetadata(
    seriesId: String,
    title: String,
    ageRating: String,
  ) {
    execute(
      """
      INSERT INTO SERIES_METADATA (
        CREATED_DATE, LAST_MODIFIED_DATE, STATUS, STATUS_LOCK,
        TITLE, TITLE_LOCK, TITLE_SORT, TITLE_SORT_LOCK,
        SUMMARY, SUMMARY_LOCK, READING_DIRECTION, READING_DIRECTION_LOCK,
        PUBLISHER, PUBLISHER_LOCK, AGE_RATING, AGE_RATING_LOCK,
        LANGUAGE, LANGUAGE_LOCK, GENRES_LOCK, TAGS_LOCK,
        TOTAL_BOOK_COUNT, TOTAL_BOOK_COUNT_LOCK, SHARING_LABELS_LOCK,
        LINKS_LOCK, ALTERNATE_TITLES_LOCK, SERIES_ID
      ) VALUES (
        '2026-07-27 10:00:00', '2026-07-27 10:02:00',
        'ONGOING', 0, '$title', 0, '$title', 0,
        'Synthetic summary', 0, 'LEFT_TO_RIGHT', 0, 'Synthetic publisher', 0,
        $ageRating, 0, 'en', 0, 0, 0, 1, 0, 0, 0, 0, '$seriesId'
      )
      """.trimIndent(),
    )
  }

  private fun Connection.insertBook(
    id: String,
    seriesId: String,
    name: String,
    numberSort: Double,
    pageCount: Int,
  ) {
    execute(
      """
      INSERT INTO BOOK VALUES (
        '$id', '2026-07-27 10:00:00', '2026-07-27 10:03:00',
        '2026-07-27 09:30:00', '$name',
        'file:///synthetic/library/$seriesId/$id.cbz', '$seriesId', 2048,
        ${numberSort.toInt()}, 'library-1', '$id-hash', NULL, 0, '$id-koreader-hash'
      )
      """.trimIndent(),
    )
    execute(
      """
      INSERT INTO BOOK_METADATA VALUES (
        '2026-07-27 10:00:00', '2026-07-27 10:03:00',
        '${numberSort.toInt()}', 0, $numberSort, 0, '2026-07-27', 0,
        'Synthetic book summary', 0, '$name', 0,
        0, 0, '$id', '', 0, 0
      )
      """.trimIndent(),
    )
    execute(
      """
      INSERT INTO MEDIA VALUES (
        'application/zip', 'READY', '2026-07-27 10:00:00',
        '2026-07-27 10:03:00', NULL, '$id', $pageCount, 0, 0
      )
      """.trimIndent(),
    )
  }

  private fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
  }

  private fun Connection.queryInt(sql: String): Int =
    createStatement().use { statement ->
      statement.executeQuery(sql).use {
        check(it.next())
        it.getInt(1)
      }
    }

  private fun XoboroDatabase.string(sql: String): String =
    requireNotNull(dsl.fetchOne(sql)?.get(0, String::class.java))

  private fun XoboroDatabase.int(sql: String): Int =
    requireNotNull(dsl.fetchOne(sql)?.get(0, Int::class.java))

  private fun sha512(value: String): String =
    MessageDigest
      .getInstance("SHA-512")
      .digest(value.encodeToByteArray())
      .joinToString("") { "%02x".format(it) }

  private fun gzip(value: String): ByteArray =
    ByteArrayOutputStream().use { buffer ->
      GZIPOutputStream(buffer).use { it.write(value.encodeToByteArray()) }
      buffer.toByteArray()
    }

  private companion object {
    val SERIES_IDS = listOf("series-1", "series-open", "series-restricted", "series-teen")
    val BOOK_IDS =
      listOf(
        "book-1",
        "book-first-position",
        "book-last-position",
        "book-open",
        "book-restricted",
        "book-teen",
      )
    val USER_IDS =
      listOf(
        "user-1",
        "user-age-allow",
        "user-age-exclude",
        "user-label-allow",
        "user-label-exclude",
      )
    const val OPENING_LOCATOR =
      """{"href":"/synthetic/opening.xhtml","type":"application/xhtml+xml",""" +
        """"locations":{"position":1,"totalProgression":0.0}}"""
    const val CLOSING_LOCATOR =
      """{"href":"/synthetic/closing.xhtml","type":"application/xhtml+xml",""" +
        """"locations":{"position":7,"totalProgression":0.857}}"""

    val SCHEMA =
      listOf(
        """CREATE TABLE flyway_schema_history(installed_rank INTEGER, version TEXT, success INTEGER)""",
        """
        CREATE TABLE LIBRARY(
          ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, NAME TEXT, ROOT TEXT,
          IMPORT_COMICINFO_BOOK INTEGER, IMPORT_COMICINFO_SERIES INTEGER,
          IMPORT_COMICINFO_COLLECTION INTEGER, IMPORT_EPUB_BOOK INTEGER,
          IMPORT_EPUB_SERIES INTEGER, SCAN_FORCE_MODIFIED_TIME INTEGER,
          SCAN_STARTUP INTEGER, IMPORT_LOCAL_ARTWORK INTEGER,
          IMPORT_COMICINFO_READLIST INTEGER, IMPORT_BARCODE_ISBN INTEGER,
          CONVERT_TO_CBZ INTEGER, REPAIR_EXTENSIONS INTEGER,
          EMPTY_TRASH_AFTER_SCAN INTEGER, IMPORT_MYLAR_SERIES INTEGER,
          SERIES_COVER TEXT, UNAVAILABLE_DATE TEXT, HASH_FILES INTEGER,
          HASH_PAGES INTEGER, ANALYZE_DIMENSIONS INTEGER,
          IMPORT_COMICINFO_SERIES_APPEND_VOLUME INTEGER, ONESHOTS_DIRECTORY TEXT,
          SCAN_CBX INTEGER, SCAN_PDF INTEGER, SCAN_EPUB INTEGER,
          SCAN_INTERVAL TEXT, HASH_KOREADER INTEGER
        )
        """.trimIndent(),
        """CREATE TABLE LIBRARY_EXCLUSIONS(LIBRARY_ID TEXT, EXCLUSION TEXT)""",
        """
        CREATE TABLE "USER"(
          ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, EMAIL TEXT,
          PASSWORD TEXT, SHARED_ALL_LIBRARIES INTEGER, AGE_RESTRICTION INTEGER,
          AGE_RESTRICTION_ALLOW_ONLY INTEGER
        )
        """.trimIndent(),
        """CREATE TABLE USER_ROLE(USER_ID TEXT, ROLE TEXT)""",
        """CREATE TABLE USER_LIBRARY_SHARING(USER_ID TEXT, LIBRARY_ID TEXT)""",
        """CREATE TABLE USER_SHARING(LABEL TEXT, ALLOW INTEGER, USER_ID TEXT)""",
        """
        CREATE TABLE SERIES(
          ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT,
          FILE_LAST_MODIFIED TEXT, NAME TEXT, URL TEXT, LIBRARY_ID TEXT,
          BOOK_COUNT INTEGER, DELETED_DATE TEXT, ONESHOT INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE SERIES_METADATA(
          CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, STATUS TEXT,
          STATUS_LOCK INTEGER, TITLE TEXT, TITLE_LOCK INTEGER, TITLE_SORT TEXT,
          TITLE_SORT_LOCK INTEGER, SUMMARY TEXT, SUMMARY_LOCK INTEGER,
          READING_DIRECTION TEXT, READING_DIRECTION_LOCK INTEGER, PUBLISHER TEXT,
          PUBLISHER_LOCK INTEGER, AGE_RATING INTEGER, AGE_RATING_LOCK INTEGER,
          LANGUAGE TEXT, LANGUAGE_LOCK INTEGER, GENRES_LOCK INTEGER,
          TAGS_LOCK INTEGER, TOTAL_BOOK_COUNT INTEGER, TOTAL_BOOK_COUNT_LOCK INTEGER,
          SHARING_LABELS_LOCK INTEGER, LINKS_LOCK INTEGER,
          ALTERNATE_TITLES_LOCK INTEGER, SERIES_ID TEXT
        )
        """.trimIndent(),
        """CREATE TABLE SERIES_METADATA_GENRE(GENRE TEXT, SERIES_ID TEXT)""",
        """CREATE TABLE SERIES_METADATA_TAG(TAG TEXT, SERIES_ID TEXT)""",
        """CREATE TABLE SERIES_METADATA_SHARING(LABEL TEXT, SERIES_ID TEXT)""",
        """CREATE TABLE SERIES_METADATA_LINK(LABEL TEXT, URL TEXT, SERIES_ID TEXT)""",
        """CREATE TABLE SERIES_METADATA_ALTERNATE_TITLE(LABEL TEXT, TITLE TEXT, SERIES_ID TEXT)""",
        """
        CREATE TABLE BOOK(
          ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT,
          FILE_LAST_MODIFIED TEXT, NAME TEXT, URL TEXT, SERIES_ID TEXT,
          FILE_SIZE INTEGER, NUMBER INTEGER, LIBRARY_ID TEXT, FILE_HASH TEXT,
          DELETED_DATE TEXT, ONESHOT INTEGER, FILE_HASH_KOREADER TEXT
        )
        """.trimIndent(),
        """
        CREATE TABLE BOOK_METADATA(
          CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, NUMBER TEXT,
          NUMBER_LOCK INTEGER, NUMBER_SORT REAL, NUMBER_SORT_LOCK INTEGER,
          RELEASE_DATE TEXT, RELEASE_DATE_LOCK INTEGER, SUMMARY TEXT,
          SUMMARY_LOCK INTEGER, TITLE TEXT, TITLE_LOCK INTEGER,
          AUTHORS_LOCK INTEGER, TAGS_LOCK INTEGER, BOOK_ID TEXT, ISBN TEXT,
          ISBN_LOCK INTEGER, LINKS_LOCK INTEGER
        )
        """.trimIndent(),
        """CREATE TABLE BOOK_METADATA_AUTHOR(NAME TEXT, ROLE TEXT, BOOK_ID TEXT)""",
        """CREATE TABLE BOOK_METADATA_TAG(TAG TEXT, BOOK_ID TEXT)""",
        """CREATE TABLE BOOK_METADATA_LINK(LABEL TEXT, URL TEXT, BOOK_ID TEXT)""",
        """
        CREATE TABLE MEDIA(
          MEDIA_TYPE TEXT, STATUS TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT,
          COMMENT TEXT, BOOK_ID TEXT, PAGE_COUNT INTEGER,
          EPUB_DIVINA_COMPATIBLE INTEGER, EPUB_IS_KEPUB INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE MEDIA_PAGE(
          FILE_NAME TEXT, MEDIA_TYPE TEXT, NUMBER INTEGER, BOOK_ID TEXT,
          WIDTH INTEGER, HEIGHT INTEGER, FILE_HASH TEXT, FILE_SIZE INTEGER
        )
        """.trimIndent(),
        """CREATE TABLE MEDIA_FILE(FILE_NAME TEXT, BOOK_ID TEXT, MEDIA_TYPE TEXT, SUB_TYPE TEXT, FILE_SIZE INTEGER)""",
        """
        CREATE TABLE READ_PROGRESS(
          BOOK_ID TEXT, USER_ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT,
          PAGE INTEGER, COMPLETED INTEGER, READ_DATE TEXT, DEVICE_ID TEXT,
          DEVICE_NAME TEXT, LOCATOR BLOB
        )
        """.trimIndent(),
        """
        CREATE TABLE READ_PROGRESS_SERIES(
          SERIES_ID TEXT, USER_ID TEXT, READ_COUNT INTEGER,
          IN_PROGRESS_COUNT INTEGER, MOST_RECENT_READ_DATE TEXT,
          LAST_MODIFIED_DATE TEXT
        )
        """.trimIndent(),
        """CREATE TABLE COLLECTION(ID TEXT, NAME TEXT, ORDERED INTEGER, SERIES_COUNT INTEGER, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT)""",
        """CREATE TABLE COLLECTION_SERIES(COLLECTION_ID TEXT, SERIES_ID TEXT, NUMBER INTEGER)""",
        """CREATE TABLE READLIST(ID TEXT, NAME TEXT, BOOK_COUNT INTEGER, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, SUMMARY TEXT, ORDERED INTEGER)""",
        """CREATE TABLE READLIST_BOOK(READLIST_ID TEXT, BOOK_ID TEXT, NUMBER INTEGER)""",
        """
        CREATE TABLE THUMBNAIL_BOOK(
          ID TEXT, THUMBNAIL BLOB, URL TEXT, SELECTED INTEGER, TYPE TEXT,
          CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, BOOK_ID TEXT,
          WIDTH INTEGER, HEIGHT INTEGER, MEDIA_TYPE TEXT, FILE_SIZE INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE THUMBNAIL_SERIES(
          ID TEXT, THUMBNAIL BLOB, URL TEXT, SELECTED INTEGER, TYPE TEXT,
          CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, SERIES_ID TEXT,
          WIDTH INTEGER, HEIGHT INTEGER, MEDIA_TYPE TEXT, FILE_SIZE INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE THUMBNAIL_COLLECTION(
          ID TEXT, THUMBNAIL BLOB, SELECTED INTEGER, TYPE TEXT,
          COLLECTION_ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT,
          WIDTH INTEGER, HEIGHT INTEGER, MEDIA_TYPE TEXT, FILE_SIZE INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE THUMBNAIL_READLIST(
          ID TEXT, THUMBNAIL BLOB, SELECTED INTEGER, TYPE TEXT,
          READLIST_ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT,
          WIDTH INTEGER, HEIGHT INTEGER, MEDIA_TYPE TEXT, FILE_SIZE INTEGER
        )
        """.trimIndent(),
        """CREATE TABLE PAGE_HASH(HASH TEXT, SIZE INTEGER, ACTION TEXT, DELETE_COUNT INTEGER, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT)""",
        """CREATE TABLE HISTORICAL_EVENT(ID TEXT, TYPE TEXT, BOOK_ID TEXT, SERIES_ID TEXT, TIMESTAMP TEXT)""",
        """CREATE TABLE HISTORICAL_EVENT_PROPERTIES(ID TEXT, KEY TEXT, VALUE TEXT)""",
        """CREATE TABLE USER_API_KEY(ID TEXT, USER_ID TEXT, CREATED_DATE TEXT, LAST_MODIFIED_DATE TEXT, API_KEY TEXT, COMMENT TEXT)""",
        """CREATE TABLE AUTHENTICATION_ACTIVITY(USER_ID TEXT, EMAIL TEXT, IP TEXT, USER_AGENT TEXT, SUCCESS INTEGER, ERROR TEXT, DATE_TIME TEXT, SOURCE TEXT, API_KEY_ID TEXT, API_KEY_COMMENT TEXT)""",
        """CREATE TABLE CLIENT_SETTINGS_GLOBAL(KEY TEXT, VALUE TEXT, ALLOW_UNAUTHORIZED INTEGER)""",
        """CREATE TABLE CLIENT_SETTINGS_USER(USER_ID TEXT, KEY TEXT, VALUE TEXT)""",
        """CREATE TABLE ANNOUNCEMENTS_READ(USER_ID TEXT, ANNOUNCEMENT_ID TEXT)""",
      )
  }
}
