package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookMediaRepository
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.MediaFile
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
      comment = record.get("comment", String::class.java),
      createdAtMillis = record.requiredLongText("created_at_ms_64"),
      updatedAtMillis = record.requiredLongText("updated_at_ms_64"),
    )
  }

  override fun upsert(media: BookMedia) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO media (
          book_id, status, media_type, profile, page_count, comment,
          created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(book_id) DO UPDATE SET
          status = excluded.status,
          media_type = excluded.media_type,
          profile = excluded.profile,
          page_count = excluded.page_count,
          comment = excluded.comment,
          created_at_ms = excluded.created_at_ms,
          updated_at_ms = excluded.updated_at_ms
        """.trimIndent(),
        media.bookId.value,
        media.status.name,
        media.mediaType,
        media.profile?.name,
        media.pageCount,
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
    }
  }

  override fun deleteByBookId(bookId: BookId) {
    database.dsl.execute("DELETE FROM media WHERE book_id = ?", bookId.value)
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
      )
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
      INSERT INTO media_file (book_id, number, file_name, media_type, file_size)
      VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
      bookId.value,
      number,
      file.fileName,
      file.mediaType,
      file.fileSize,
    )
  }

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.nullableLongText(field: String): Long? =
    get(field, String::class.java)?.toLong()
}
