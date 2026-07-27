package io.xoboro.server.persistence

import io.xoboro.core.domain.Author
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMetadata
import io.xoboro.core.domain.BookMetadataRepository
import io.xoboro.core.domain.WebLink
import org.jooq.DSLContext
import org.jooq.Record

class JooqBookMetadataRepository(
  private val database: XoboroDatabase,
) : BookMetadataRepository {
  override fun findByBookIdOrNull(bookId: BookId): BookMetadata? {
    val record =
      database.dsl
        .fetchOne(
          """
          SELECT book_metadata.*,
            CAST(created_at_ms AS TEXT) AS created_at_ms_64,
            CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
          FROM book_metadata
          WHERE book_id = ?
          """.trimIndent(),
          bookId.value,
        )
        ?: return null
    return record.toMetadata(
      authors = loadAuthors(bookId),
      tags = loadTags(bookId),
      links = loadLinks(bookId),
    )
  }

  override fun upsert(metadata: BookMetadata) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO book_metadata (
          book_id, title, summary, number, number_sort, release_date, isbn,
          title_lock, summary_lock, number_lock, number_sort_lock,
          release_date_lock, authors_lock, tags_lock, isbn_lock, links_lock,
          created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(book_id) DO UPDATE SET
          title = excluded.title,
          summary = excluded.summary,
          number = excluded.number,
          number_sort = excluded.number_sort,
          release_date = excluded.release_date,
          isbn = excluded.isbn,
          title_lock = excluded.title_lock,
          summary_lock = excluded.summary_lock,
          number_lock = excluded.number_lock,
          number_sort_lock = excluded.number_sort_lock,
          release_date_lock = excluded.release_date_lock,
          authors_lock = excluded.authors_lock,
          tags_lock = excluded.tags_lock,
          isbn_lock = excluded.isbn_lock,
          links_lock = excluded.links_lock,
          created_at_ms = excluded.created_at_ms,
          updated_at_ms = excluded.updated_at_ms
        """.trimIndent(),
        metadata.bookId.value,
        metadata.normalizedTitle,
        metadata.normalizedSummary,
        metadata.normalizedNumber,
        metadata.numberSort,
        metadata.releaseDate,
        metadata.isbn.trim(),
        metadata.titleLock.toSqliteInt(),
        metadata.summaryLock.toSqliteInt(),
        metadata.numberLock.toSqliteInt(),
        metadata.numberSortLock.toSqliteInt(),
        metadata.releaseDateLock.toSqliteInt(),
        metadata.authorsLock.toSqliteInt(),
        metadata.tagsLock.toSqliteInt(),
        metadata.isbnLock.toSqliteInt(),
        metadata.linksLock.toSqliteInt(),
        metadata.createdAtMillis,
        metadata.updatedAtMillis,
      )
      transaction.replaceAuthors(metadata)
      transaction.replaceTags(metadata)
      transaction.replaceLinks(metadata)
    }
  }

  private fun loadAuthors(bookId: BookId): List<Author> =
    database.dsl
      .fetch(
        """
        SELECT name, role
        FROM book_metadata_author
        WHERE book_id = ?
        ORDER BY ordinal
        """.trimIndent(),
        bookId.value,
      )
      .map { Author(it.requiredString("name"), it.requiredString("role")) }

  private fun loadTags(bookId: BookId): Set<String> =
    database.dsl
      .fetch(
        """
        SELECT tag
        FROM book_metadata_tag
        WHERE book_id = ?
        ORDER BY tag
        """.trimIndent(),
        bookId.value,
      ).map { it.requiredString("tag") }.toSet()

  private fun loadLinks(bookId: BookId): List<WebLink> =
    database.dsl
      .fetch(
        """
        SELECT label, url
        FROM book_metadata_link
        WHERE book_id = ?
        ORDER BY ordinal
        """.trimIndent(),
        bookId.value,
      )
      .map { WebLink(it.requiredString("label"), it.requiredString("url")) }

  private fun DSLContext.replaceAuthors(metadata: BookMetadata) {
    execute("DELETE FROM book_metadata_author WHERE book_id = ?", metadata.bookId.value)
    metadata.authors.forEachIndexed { index, author ->
      execute(
        """
        INSERT INTO book_metadata_author (book_id, ordinal, name, role)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
        metadata.bookId.value,
        index,
        author.normalizedName,
        author.normalizedRole,
      )
    }
  }

  private fun DSLContext.replaceTags(metadata: BookMetadata) {
    execute("DELETE FROM book_metadata_tag WHERE book_id = ?", metadata.bookId.value)
    metadata.normalizedTags.sorted().forEach { tag ->
      execute(
        "INSERT INTO book_metadata_tag (book_id, tag) VALUES (?, ?)",
        metadata.bookId.value,
        tag,
      )
    }
  }

  private fun DSLContext.replaceLinks(metadata: BookMetadata) {
    execute("DELETE FROM book_metadata_link WHERE book_id = ?", metadata.bookId.value)
    metadata.links.forEachIndexed { index, link ->
      execute(
        """
        INSERT INTO book_metadata_link (book_id, ordinal, label, url)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
        metadata.bookId.value,
        index,
        link.label,
        link.url,
      )
    }
  }

  private fun Record.toMetadata(
    authors: List<Author>,
    tags: Set<String>,
    links: List<WebLink>,
  ): BookMetadata =
    BookMetadata(
      bookId = BookId(requiredString("book_id")),
      title = requiredString("title"),
      summary = requiredString("summary"),
      number = requiredString("number"),
      numberSort = requiredDouble("number_sort").toFloat(),
      releaseDate = get("release_date", String::class.java),
      authors = authors,
      tags = tags,
      isbn = requiredString("isbn"),
      links = links,
      titleLock = requiredBoolean("title_lock"),
      summaryLock = requiredBoolean("summary_lock"),
      numberLock = requiredBoolean("number_lock"),
      numberSortLock = requiredBoolean("number_sort_lock"),
      releaseDateLock = requiredBoolean("release_date_lock"),
      authorsLock = requiredBoolean("authors_lock"),
      tagsLock = requiredBoolean("tags_lock"),
      isbnLock = requiredBoolean("isbn_lock"),
      linksLock = requiredBoolean("links_lock"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredDouble(field: String): Double =
    requireNotNull(get(field, Double::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requireNotNull(get(field, Int::class.java))) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be 0 or 1, got $value")
    }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0
}
