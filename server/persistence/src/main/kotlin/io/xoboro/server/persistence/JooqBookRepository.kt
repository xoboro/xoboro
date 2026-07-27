package io.xoboro.server.persistence

import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookRepository
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext
import org.jooq.Record

class JooqBookRepository(
  private val database: XoboroDatabase,
) : BookRepository {
  override fun findByIdOrNull(id: BookId): Book? =
    database.dsl.fetchOne("$SELECT_BOOK WHERE id = ?", id.value)?.toBook()

  override fun findAllByLibraryId(libraryId: LibraryId): List<Book> =
    database.dsl
      .fetch(
        "$SELECT_BOOK WHERE library_id = ? ORDER BY relative_uri, id",
        libraryId.value,
      )
      .map { it.toBook() }

  override fun findAllBySeriesId(seriesId: SeriesId): List<Book> =
    database.dsl
      .fetch(
        "$SELECT_BOOK WHERE series_id = ? ORDER BY number, relative_uri, id",
        seriesId.value,
      )
      .map { it.toBook() }

  override fun findByLibraryIdAndRelativePath(
    libraryId: LibraryId,
    relativePath: String,
  ): Book? {
    require(relativePath.isNotBlank()) { "Book relative path must not be blank" }
    return database.dsl
      .fetchOne(
        "$SELECT_BOOK WHERE library_id = ? AND relative_uri = ?",
        libraryId.value,
        relativePath,
      )
      ?.toBook()
  }

  override fun insert(book: Book) {
    database.dsl.insertBook(book)
  }

  override fun insertAll(books: Collection<Book>) {
    if (books.isEmpty()) return
    database.transaction { transaction ->
      books.forEach { transaction.insertBook(it) }
    }
  }

  override fun update(book: Book) {
    if (database.dsl.updateBook(book) == 0) {
      throw NoSuchElementException("Book not found: ${book.id.value}")
    }
  }

  override fun updateAll(books: Collection<Book>) {
    if (books.isEmpty()) return
    database.transaction { transaction ->
      books.forEach { item ->
        if (transaction.updateBook(item) == 0) {
          throw NoSuchElementException("Book not found: ${item.id.value}")
        }
      }
    }
  }

  override fun delete(id: BookId) {
    database.dsl.execute("DELETE FROM book WHERE id = ?", id.value)
  }

  override fun count(): Long =
    database.dsl.fetchOne("SELECT count(*) FROM book")?.get(0)?.let { it as Number }?.toLong()
      ?: 0L

  private fun DSLContext.insertBook(book: Book) {
    execute(
      """
      INSERT INTO book (
        id, library_id, series_id, relative_uri, source_item_id, source_identity,
        name, media_kind, media_item_type, file_size, file_modified_ms,
        file_hash, file_hash_koreader,
        number, deleted_at_ms, oneshot, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      *book.bindValues(),
    )
  }

  private fun DSLContext.updateBook(book: Book): Int =
    execute(
      """
      UPDATE book SET
        library_id = ?, series_id = ?, relative_uri = ?, source_item_id = ?,
        source_identity = ?, name = ?, media_kind = ?, file_size = ?,
        media_item_type = CASE
          WHEN media_item_type = CASE media_kind
            WHEN 'COMIC_ARCHIVE' THEN 'COMIC'
            WHEN 'EPUB' THEN 'NOVEL'
            ELSE 'BOOK'
          END
          THEN ? ELSE media_item_type
        END,
        file_modified_ms = ?, file_hash = ?,
        file_hash_koreader = ?, number = ?,
        deleted_at_ms = ?, oneshot = ?, updated_at_ms = ?
      WHERE id = ?
      """.trimIndent(),
      *book.updateBindValues(),
    )

  private fun Book.bindValues(): Array<Any?> =
    arrayOf(
      id.value,
      libraryId.value,
      seriesId.value,
      relativePath,
      sourceItemId,
      sourceIdentity,
      name,
      mediaKind.name,
      mediaKind.defaultMediaItemType(),
      fileSize,
      fileModifiedAtMillis,
      fileHash,
      fileHashKoreader,
      number,
      deletedAtMillis,
      oneshot.toSqliteInt(),
      createdAtMillis,
      updatedAtMillis,
    )

  private fun Book.updateBindValues(): Array<Any?> =
    arrayOf(
      libraryId.value,
      seriesId.value,
      relativePath,
      sourceItemId,
      sourceIdentity,
      name,
      mediaKind.name,
      fileSize,
      mediaKind.defaultMediaItemType(),
      fileModifiedAtMillis,
      fileHash,
      fileHashKoreader,
      number,
      deletedAtMillis,
      oneshot.toSqliteInt(),
      updatedAtMillis,
      id.value,
    )

  private fun Record.toBook(): Book =
    Book(
      id = BookId(requiredString("id")),
      libraryId = LibraryId(requiredString("library_id")),
      seriesId = SeriesId(requiredString("series_id")),
      name = requiredString("name"),
      relativePath = requiredString("relative_uri"),
      sourceItemId = requiredString("source_item_id"),
      sourceIdentity = get("source_identity", String::class.java),
      mediaKind = MediaKind.valueOf(requiredString("media_kind")),
      fileModifiedAtMillis = requiredLongText("file_modified_ms_64"),
      fileSize = requiredLongText("file_size_64"),
      fileHash = requiredString("file_hash"),
      fileHashKoreader = requiredString("file_hash_koreader"),
      number = requiredInt("number"),
      deletedAtMillis = nullableLongText("deleted_at_ms_64"),
      oneshot = requiredBoolean("oneshot"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.nullableLongText(field: String): Long? =
    get(field, String::class.java)?.toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requiredInt(field)) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be 0 or 1, got $value")
    }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  private fun MediaKind.defaultMediaItemType(): String =
    when (this) {
      MediaKind.COMIC_ARCHIVE -> "COMIC"
      MediaKind.EPUB -> "NOVEL"
      MediaKind.PDF -> "BOOK"
    }

  companion object {
    private const val SELECT_BOOK =
      """
      SELECT book.*,
        CAST(file_size AS TEXT) AS file_size_64,
        CAST(file_modified_ms AS TEXT) AS file_modified_ms_64,
        CAST(deleted_at_ms AS TEXT) AS deleted_at_ms_64,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM book
      """
  }
}
