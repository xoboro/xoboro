package io.xoboro.server.persistence

import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesRepository
import org.jooq.DSLContext
import org.jooq.Record

class JooqSeriesRepository(
  private val database: XoboroDatabase,
) : SeriesRepository {
  override fun findByIdOrNull(id: SeriesId): Series? =
    database.dsl.fetchOne("$SELECT_SERIES WHERE id = ?", id.value)?.toSeries()

  override fun findAllByLibraryId(libraryId: LibraryId): List<Series> =
    database.dsl
      .fetch(
        "$SELECT_SERIES WHERE library_id = ? ORDER BY relative_uri, id",
        libraryId.value,
      )
      .map { it.toSeries() }

  override fun findByLibraryIdAndRelativePath(
    libraryId: LibraryId,
    relativePath: String,
  ): Series? {
    require(relativePath.isNotBlank()) { "Series relative path must not be blank" }
    return database.dsl
      .fetchOne(
        "$SELECT_SERIES WHERE library_id = ? AND relative_uri = ?",
        libraryId.value,
        relativePath,
      )
      ?.toSeries()
  }

  override fun insert(series: Series) {
    database.dsl.insertSeries(series)
  }

  override fun insertAll(series: Collection<Series>) {
    if (series.isEmpty()) return
    database.transaction { transaction ->
      series.forEach { transaction.insertSeries(it) }
    }
  }

  override fun update(series: Series) {
    if (database.dsl.updateSeries(series) == 0) {
      throw NoSuchElementException("Series not found: ${series.id.value}")
    }
  }

  override fun updateAll(series: Collection<Series>) {
    if (series.isEmpty()) return
    database.transaction { transaction ->
      series.forEach { item ->
        if (transaction.updateSeries(item) == 0) {
          throw NoSuchElementException("Series not found: ${item.id.value}")
        }
      }
    }
  }

  override fun delete(id: SeriesId) {
    database.dsl.execute("DELETE FROM series WHERE id = ?", id.value)
  }

  override fun count(): Long =
    database.dsl.fetchOne("SELECT count(*) FROM series")?.get(0, Long::class.java) ?: 0L

  private fun DSLContext.insertSeries(series: Series) {
    execute(
      """
      INSERT INTO series (
        id, library_id, relative_uri, source_item_id, name, sort_title,
        file_modified_ms, book_count, deleted_at_ms, oneshot, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      series.id.value,
      series.libraryId.value,
      series.relativePath,
      series.sourceItemId,
      series.name,
      series.name,
      series.fileModifiedAtMillis,
      series.bookCount,
      series.deletedAtMillis,
      series.oneshot.toSqliteInt(),
      series.createdAtMillis,
      series.updatedAtMillis,
    )
  }

  private fun DSLContext.updateSeries(series: Series): Int =
    execute(
      """
      UPDATE series SET
        library_id = ?, relative_uri = ?, source_item_id = ?, name = ?,
        file_modified_ms = ?, book_count = ?, deleted_at_ms = ?, oneshot = ?,
        updated_at_ms = ?
      WHERE id = ?
      """.trimIndent(),
      series.libraryId.value,
      series.relativePath,
      series.sourceItemId,
      series.name,
      series.fileModifiedAtMillis,
      series.bookCount,
      series.deletedAtMillis,
      series.oneshot.toSqliteInt(),
      series.updatedAtMillis,
      series.id.value,
    )

  private fun Record.toSeries(): Series =
    Series(
      id = SeriesId(requiredString("id")),
      libraryId = LibraryId(requiredString("library_id")),
      name = requiredString("name"),
      relativePath = requiredString("relative_uri"),
      sourceItemId = requiredString("source_item_id"),
      fileModifiedAtMillis = requiredLong("file_modified_ms"),
      bookCount = requiredInt("book_count"),
      deletedAtMillis = get("deleted_at_ms", Long::class.java),
      oneshot = requiredBoolean("oneshot"),
      createdAtMillis = requiredLong("created_at_ms"),
      updatedAtMillis = requiredLong("updated_at_ms"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLong(field: String): Long =
    requireNotNull(get(field, Long::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requiredInt(field)) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be 0 or 1, got $value")
    }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  companion object {
    private const val SELECT_SERIES = "SELECT * FROM series"
  }
}
