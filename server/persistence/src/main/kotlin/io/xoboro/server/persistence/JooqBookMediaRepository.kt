package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaNavigationEntry
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import org.jooq.DSLContext
import org.jooq.Record

class JooqBookMediaRepository(
  private val database: XoboroDatabase,
) : BookMediaRepository {
  override fun findByBookIdOrNull(bookId: BookId): BookMedia? {
    val record =
      database.dsl.fetchOne(
        """
        SELECT media.*,
          CAST(created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
        FROM media
        WHERE book_id = ?
        """.trimIndent(),
        bookId.value,
      ) ?: return null
    return BookMedia(
      bookId = bookId,
      status = MediaStatus.valueOf(record.requiredString("status")),
      mediaType = record.get("media_type", String::class.java),
      profile = record.get("profile", String::class.java)?.let(MediaProfile::valueOf),
      pages = database.dsl.findPages(bookId),
      pageCount = record.requiredInt("page_count"),
      files = database.dsl.findFiles(bookId),
      epubDivinaCompatible = record.requiredBoolean("epub_divina_compatible"),
      epubIsKepub = record.requiredBoolean("epub_is_kepub"),
      epubIsFixedLayout = record.requiredBoolean("epub_is_fixed_layout"),
      toc = database.dsl.findNavigation(bookId, "TOC"),
      landmarks = database.dsl.findNavigation(bookId, "LANDMARK"),
      pageList = database.dsl.findNavigation(bookId, "PAGE_LIST"),
      positions = database.dsl.findPositions(bookId),
      comment = record.get("comment", String::class.java),
      createdAtMillis = record.requiredLongText("created_at_ms_64"),
      updatedAtMillis = record.requiredLongText("updated_at_ms_64"),
    )
  }

  override fun findAllByBookIds(bookIds: Collection<BookId>): List<BookMedia> =
    bookIds
      .distinct()
      .chunked(QUERY_BATCH_SIZE)
      .flatMap(::findBatch)

  private fun findBatch(bookIds: List<BookId>): List<BookMedia> {
    if (bookIds.isEmpty()) return emptyList()
    val records =
      database.dsl.fetch(
        """
        SELECT media.*,
          CAST(created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
        FROM media
        WHERE book_id IN (${bookIds.placeholders()})
        ORDER BY book_id
        """.trimIndent(),
        *bookIds.map { it.value }.toTypedArray(),
      )
    val pages = database.dsl.findPagesBatch(bookIds)
    val files = database.dsl.findFilesBatch(bookIds)
    val positions = database.dsl.findPositionsBatch(bookIds)
    val toc = database.dsl.findNavigationBatch(bookIds, "TOC")
    val landmarks = database.dsl.findNavigationBatch(bookIds, "LANDMARK")
    val pageLists = database.dsl.findNavigationBatch(bookIds, "PAGE_LIST")
    return records.map { record ->
      val id = BookId(record.requiredString("book_id"))
      BookMedia(
        bookId = id,
        status = MediaStatus.valueOf(record.requiredString("status")),
        mediaType = record.get("media_type", String::class.java),
        profile = record.get("profile", String::class.java)?.let(MediaProfile::valueOf),
        pages = pages[id].orEmpty(),
        pageCount = record.requiredInt("page_count"),
        files = files[id].orEmpty(),
        epubDivinaCompatible = record.requiredBoolean("epub_divina_compatible"),
        epubIsKepub = record.requiredBoolean("epub_is_kepub"),
        epubIsFixedLayout = record.requiredBoolean("epub_is_fixed_layout"),
        toc = toc[id].orEmpty(),
        landmarks = landmarks[id].orEmpty(),
        pageList = pageLists[id].orEmpty(),
        positions = positions[id].orEmpty(),
        comment = record.get("comment", String::class.java),
        createdAtMillis = record.requiredLongText("created_at_ms_64"),
        updatedAtMillis = record.requiredLongText("updated_at_ms_64"),
      )
    }
  }

  override fun upsert(media: BookMedia) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO media (
          book_id, status, media_type, profile, page_count,
          epub_divina_compatible, epub_is_kepub, epub_is_fixed_layout, comment,
          created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(book_id) DO UPDATE SET
          status = excluded.status,
          media_type = excluded.media_type,
          profile = excluded.profile,
          page_count = excluded.page_count,
          epub_divina_compatible = excluded.epub_divina_compatible,
          epub_is_kepub = excluded.epub_is_kepub,
          epub_is_fixed_layout = excluded.epub_is_fixed_layout,
          comment = excluded.comment,
          created_at_ms = excluded.created_at_ms,
          updated_at_ms = excluded.updated_at_ms
        """.trimIndent(),
        media.bookId.value,
        media.status.name,
        media.mediaType,
        media.profile?.name,
        media.pageCount,
        media.epubDivinaCompatible.toSqliteInt(),
        media.epubIsKepub.toSqliteInt(),
        media.epubIsFixedLayout.toSqliteInt(),
        media.comment,
        media.createdAtMillis,
        media.updatedAtMillis,
      )
      transaction.execute("DELETE FROM book_page WHERE book_id = ?", media.bookId.value)
      media.pages.forEach { page -> transaction.insertPage(media.bookId, page) }
      transaction.execute("DELETE FROM media_file WHERE book_id = ?", media.bookId.value)
      media.files.forEachIndexed { index, file ->
        transaction.insertFile(media.bookId, number = index + 1, file = file)
      }
      transaction.execute("DELETE FROM media_position WHERE book_id = ?", media.bookId.value)
      media.positions.forEach { position -> transaction.insertPosition(media.bookId, position) }
      transaction.execute(
        "DELETE FROM media_navigation_entry WHERE book_id = ?",
        media.bookId.value,
      )
      transaction.insertNavigation(media.bookId, "TOC", media.toc)
      transaction.insertNavigation(media.bookId, "LANDMARK", media.landmarks)
      transaction.insertNavigation(media.bookId, "PAGE_LIST", media.pageList)
    }
  }

  override fun deleteByBookId(bookId: BookId) {
    database.dsl.execute("DELETE FROM media WHERE book_id = ?", bookId.value)
  }

  private fun DSLContext.findPagesBatch(
    bookIds: Collection<BookId>,
  ): Map<BookId, List<BookPage>> =
    fetch(
      """
      SELECT book_page.*,
        CAST(file_size AS TEXT) AS file_size_64
      FROM book_page
      WHERE book_id IN (${bookIds.placeholders()})
      ORDER BY book_id, number
      """.trimIndent(),
      *bookIds.map { it.value }.toTypedArray(),
    ).groupBy(
      { BookId(it.requiredString("book_id")) },
      { record ->
        val width = (record.get("width") as? Number)?.toInt()
        val height = (record.get("height") as? Number)?.toInt()
        BookPage(
          number = record.requiredInt("number"),
          fileName = record.requiredString("file_name"),
          mediaType = record.requiredString("media_type"),
          fileSize = record.nullableLongText("file_size_64"),
          dimension =
            if (width != null && height != null) {
              Dimension(width, height)
            } else {
              null
            },
          fileHash = record.requiredString("file_hash"),
        )
      },
    )

  private fun DSLContext.findFilesBatch(
    bookIds: Collection<BookId>,
  ): Map<BookId, List<MediaFile>> =
    fetch(
      """
      SELECT media_file.*,
        CAST(file_size AS TEXT) AS file_size_64
      FROM media_file
      WHERE book_id IN (${bookIds.placeholders()})
      ORDER BY book_id, number
      """.trimIndent(),
      *bookIds.map { it.value }.toTypedArray(),
    ).groupBy(
      { BookId(it.requiredString("book_id")) },
      { record ->
        MediaFile(
          fileName = record.requiredString("file_name"),
          mediaType = record.get("media_type", String::class.java),
          fileSize = record.nullableLongText("file_size_64"),
          kind = MediaFileKind.valueOf(record.requiredString("kind")),
        )
      },
    )

  private fun DSLContext.findPositionsBatch(
    bookIds: Collection<BookId>,
  ): Map<BookId, List<MediaPosition>> =
    fetch(
      """
      SELECT *
      FROM media_position
      WHERE book_id IN (${bookIds.placeholders()})
      ORDER BY book_id, position
      """.trimIndent(),
      *bookIds.map { it.value }.toTypedArray(),
    ).groupBy(
      { BookId(it.requiredString("book_id")) },
      { record ->
        MediaPosition(
          href = record.requiredString("href"),
          mediaType = record.requiredString("media_type"),
          progression = record.requiredFloat("progression"),
          position = record.requiredInt("position"),
          totalProgression = record.requiredFloat("total_progression"),
          koboSpan = record.get("kobo_span", String::class.java),
        )
      },
    )

  private fun DSLContext.findNavigationBatch(
    bookIds: Collection<BookId>,
    type: String,
  ): Map<BookId, List<MediaNavigationEntry>> {
    val rows =
      fetch(
        """
        SELECT book_id, path, parent_path, title, href
        FROM media_navigation_entry
        WHERE book_id IN (${bookIds.placeholders()}) AND navigation_type = ?
        ORDER BY book_id, path
        """.trimIndent(),
        *(bookIds.map { it.value } + type).toTypedArray(),
      ).map { record ->
        BookNavigationRow(
          bookId = BookId(record.requiredString("book_id")),
          row =
            NavigationRow(
              path = record.requiredString("path"),
              parentPath = record.get("parent_path", String::class.java),
              title = record.requiredString("title"),
              href = record.get("href", String::class.java),
            ),
        )
      }
    return rows.groupBy(BookNavigationRow::bookId).mapValues { (_, bookRows) ->
      val childrenByParent = bookRows.map(BookNavigationRow::row).groupBy(NavigationRow::parentPath)
      fun descendants(parentPath: String?): List<MediaNavigationEntry> =
        childrenByParent[parentPath].orEmpty().map { row ->
          MediaNavigationEntry(
            title = row.title,
            href = row.href,
            children = descendants(row.path),
          )
        }
      descendants(null)
    }
  }

  private fun DSLContext.findPages(bookId: BookId): List<BookPage> =
    fetch(
      """
      SELECT book_page.*,
        CAST(file_size AS TEXT) AS file_size_64
      FROM book_page
      WHERE book_id = ?
      ORDER BY number
      """.trimIndent(),
      bookId.value,
    ).map { record ->
      val width = (record.get("width") as? Number)?.toInt()
      val height = (record.get("height") as? Number)?.toInt()
      BookPage(
        number = record.requiredInt("number"),
        fileName = record.requiredString("file_name"),
        mediaType = record.requiredString("media_type"),
        fileSize = record.nullableLongText("file_size_64"),
        dimension =
          if (width != null && height != null) {
            Dimension(width, height)
          } else {
            null
          },
        fileHash = record.requiredString("file_hash"),
      )
    }

  private fun DSLContext.findFiles(bookId: BookId): List<MediaFile> =
    fetch(
      """
      SELECT media_file.*,
        CAST(file_size AS TEXT) AS file_size_64
      FROM media_file
      WHERE book_id = ?
      ORDER BY number
      """.trimIndent(),
      bookId.value,
    ).map { record ->
      MediaFile(
        fileName = record.requiredString("file_name"),
        mediaType = record.get("media_type", String::class.java),
        fileSize = record.nullableLongText("file_size_64"),
        kind = MediaFileKind.valueOf(record.requiredString("kind")),
      )
    }

  private fun DSLContext.findPositions(bookId: BookId): List<MediaPosition> =
    fetch(
      """
      SELECT *
      FROM media_position
      WHERE book_id = ?
      ORDER BY position
      """.trimIndent(),
      bookId.value,
    ).map { record ->
      MediaPosition(
        href = record.requiredString("href"),
        mediaType = record.requiredString("media_type"),
        progression = record.requiredFloat("progression"),
        position = record.requiredInt("position"),
        totalProgression = record.requiredFloat("total_progression"),
        koboSpan = record.get("kobo_span", String::class.java),
      )
    }

  private fun DSLContext.findNavigation(
    bookId: BookId,
    type: String,
  ): List<MediaNavigationEntry> {
    val rows =
      fetch(
        """
        SELECT path, parent_path, title, href
        FROM media_navigation_entry
        WHERE book_id = ? AND navigation_type = ?
        ORDER BY path
        """.trimIndent(),
        bookId.value,
        type,
      ).map { record ->
        NavigationRow(
          path = record.requiredString("path"),
          parentPath = record.get("parent_path", String::class.java),
          title = record.requiredString("title"),
          href = record.get("href", String::class.java),
        )
      }
    val childrenByParent = rows.groupBy(NavigationRow::parentPath)
    fun descendants(parentPath: String?): List<MediaNavigationEntry> =
      childrenByParent[parentPath].orEmpty().map { row ->
        MediaNavigationEntry(
          title = row.title,
          href = row.href,
          children = descendants(row.path),
        )
      }
    return descendants(null)
  }

  private fun DSLContext.insertPage(
    bookId: BookId,
    page: BookPage,
  ) {
    execute(
      """
      INSERT INTO book_page (
        book_id, number, file_name, media_type, file_size, width, height, file_hash
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      bookId.value,
      page.number,
      page.fileName,
      page.mediaType,
      page.fileSize,
      page.dimension?.width,
      page.dimension?.height,
      page.fileHash,
    )
  }

  private fun DSLContext.insertFile(
    bookId: BookId,
    number: Int,
    file: MediaFile,
  ) {
    execute(
      """
      INSERT INTO media_file (book_id, number, file_name, media_type, file_size, kind)
      VALUES (?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      bookId.value,
      number,
      file.fileName,
      file.mediaType,
      file.fileSize,
      file.kind.name,
    )
  }

  private fun DSLContext.insertPosition(
    bookId: BookId,
    position: MediaPosition,
  ) {
    execute(
      """
      INSERT INTO media_position (
        book_id, position, href, media_type, progression, total_progression, kobo_span
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      bookId.value,
      position.position,
      position.href,
      position.mediaType,
      position.progression,
      position.totalProgression,
      position.koboSpan,
    )
  }

  private fun DSLContext.insertNavigation(
    bookId: BookId,
    type: String,
    entries: List<MediaNavigationEntry>,
    parentPath: String? = null,
  ) {
    entries.forEachIndexed { index, entry ->
      val path = parentPath?.let { "$it.${index + 1}" } ?: "${index + 1}"
      execute(
        """
        INSERT INTO media_navigation_entry (
          book_id, navigation_type, path, parent_path, title, href
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        bookId.value,
        type,
        path,
        parentPath,
        entry.title,
        entry.href,
      )
      insertNavigation(bookId, type, entry.children, path)
    }
  }

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.requiredFloat(field: String): Float =
    requireNotNull(get(field, Float::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredBoolean(field: String): Boolean =
    when (requiredInt(field)) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be zero or one")
    }

  private fun Record.nullableLongText(field: String): Long? =
    get(field, String::class.java)?.toLong()

  private fun Collection<*>.placeholders(): String = joinToString(",") { "?" }

  private data class BookNavigationRow(
    val bookId: BookId,
    val row: NavigationRow,
  )

  private data class NavigationRow(
    val path: String,
    val parentPath: String?,
    val title: String,
    val href: String?,
  )

  private companion object {
    const val QUERY_BATCH_SIZE = 500
  }
}

private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0
