package io.xoboro.server.persistence

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import org.jooq.DSLContext
import org.jooq.Record

class JooqSeriesCollectionRepository(
  private val database: XoboroDatabase,
) : SeriesCollectionRepository {
  override fun findByIdOrNull(id: CollectionId): SeriesCollection? =
    database.dsl
      .fetchOne("$SELECT_COLLECTION WHERE id = ?", id.value)
      ?.let { it.toCollection(loadMembers(listOf(id))[id].orEmpty()) }

  override fun findAll(): List<SeriesCollection> =
    database.dsl
      .fetch("$SELECT_COLLECTION ORDER BY name COLLATE NOCASE, id")
      .let(::hydrate)

  override fun findAllBySeriesId(seriesId: SeriesId): List<SeriesCollection> =
    database.dsl
      .fetch(
        """
        $SELECT_COLLECTION
        WHERE id IN (
          SELECT collection_id
          FROM series_collection_member
          WHERE series_id = ?
        )
        ORDER BY name COLLATE NOCASE, id
        """.trimIndent(),
        seriesId.value,
      ).let(::hydrate)

  override fun findByNameIgnoreCaseOrNull(name: String): SeriesCollection? =
    database.dsl
      .fetchOne("$SELECT_COLLECTION WHERE name = ? COLLATE NOCASE", name)
      ?.let { record ->
        val id = CollectionId(record.requiredString("id"))
        record.toCollection(loadMembers(listOf(id))[id].orEmpty())
      }

  override fun insert(collection: SeriesCollection) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO series_collection
          (id, name, ordered, created_at_ms, updated_at_ms)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        collection.id.value,
        collection.name,
        collection.ordered.toSqliteInt(),
        collection.createdAtMillis,
        collection.updatedAtMillis,
      )
      transaction.replaceCollectionMembers(collection)
    }
  }

  override fun update(collection: SeriesCollection) {
    database.transaction { transaction ->
      val affected =
        transaction.execute(
          """
          UPDATE series_collection
          SET name = ?, ordered = ?, updated_at_ms = ?
          WHERE id = ?
          """.trimIndent(),
          collection.name,
          collection.ordered.toSqliteInt(),
          collection.updatedAtMillis,
          collection.id.value,
        )
      if (affected == 0) throw NoSuchElementException("Collection not found")
      transaction.replaceCollectionMembers(collection)
    }
  }

  override fun delete(id: CollectionId) {
    database.dsl.execute("DELETE FROM series_collection WHERE id = ?", id.value)
  }

  private fun hydrate(records: List<Record>): List<SeriesCollection> {
    val ids = records.map { CollectionId(it.requiredString("id")) }
    val members = loadMembers(ids)
    return records.map { record ->
      val id = CollectionId(record.requiredString("id"))
      record.toCollection(members[id].orEmpty())
    }
  }

  private fun loadMembers(ids: Collection<CollectionId>): Map<CollectionId, List<SeriesId>> {
    if (ids.isEmpty()) return emptyMap()
    val values = ids.map(CollectionId::value)
    val placeholders = values.joinToString(",") { "?" }
    return database.dsl
      .fetch(
        """
        SELECT collection_id, series_id
        FROM series_collection_member
        WHERE collection_id IN ($placeholders)
        ORDER BY collection_id, position
        """.trimIndent(),
        *values.toTypedArray(),
      ).groupBy(
        keySelector = { CollectionId(it.requiredString("collection_id")) },
        valueTransform = { SeriesId(it.requiredString("series_id")) },
      )
  }

  private fun DSLContext.replaceCollectionMembers(collection: SeriesCollection) {
    execute(
      "DELETE FROM series_collection_member WHERE collection_id = ?",
      collection.id.value,
    )
    collection.seriesIds.forEachIndexed { position, seriesId ->
      execute(
        """
        INSERT INTO series_collection_member (collection_id, series_id, position)
        VALUES (?, ?, ?)
        """.trimIndent(),
        collection.id.value,
        seriesId.value,
        position,
      )
    }
  }

  private fun Record.toCollection(seriesIds: List<SeriesId>): SeriesCollection =
    SeriesCollection(
      id = CollectionId(requiredString("id")),
      name = requiredString("name"),
      ordered = requiredBoolean("ordered"),
      seriesIds = seriesIds,
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private companion object {
    const val SELECT_COLLECTION =
      """
      SELECT series_collection.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM series_collection
      """
  }
}

class JooqReadListRepository(
  private val database: XoboroDatabase,
) : ReadListRepository {
  override fun findByIdOrNull(id: ReadListId): ReadList? =
    database.dsl
      .fetchOne("$SELECT_READ_LIST WHERE id = ?", id.value)
      ?.let { it.toReadList(loadMembers(listOf(id))[id].orEmpty()) }

  override fun findAll(): List<ReadList> =
    database.dsl
      .fetch("$SELECT_READ_LIST ORDER BY name COLLATE NOCASE, id")
      .let(::hydrate)

  override fun findAllByBookId(bookId: BookId): List<ReadList> =
    database.dsl
      .fetch(
        """
        $SELECT_READ_LIST
        WHERE id IN (
          SELECT read_list_id
          FROM read_list_member
          WHERE book_id = ?
        )
        ORDER BY name COLLATE NOCASE, id
        """.trimIndent(),
        bookId.value,
      ).let(::hydrate)

  override fun findByNameIgnoreCaseOrNull(name: String): ReadList? =
    database.dsl
      .fetchOne("$SELECT_READ_LIST WHERE name = ? COLLATE NOCASE", name)
      ?.let { record ->
        val id = ReadListId(record.requiredString("id"))
        record.toReadList(loadMembers(listOf(id))[id].orEmpty())
      }

  override fun insert(readList: ReadList) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO read_list
          (id, name, summary, ordered, created_at_ms, updated_at_ms)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        readList.id.value,
        readList.name,
        readList.summary,
        readList.ordered.toSqliteInt(),
        readList.createdAtMillis,
        readList.updatedAtMillis,
      )
      transaction.replaceReadListMembers(readList)
    }
  }

  override fun update(readList: ReadList) {
    database.transaction { transaction ->
      val affected =
        transaction.execute(
          """
          UPDATE read_list
          SET name = ?, summary = ?, ordered = ?, updated_at_ms = ?
          WHERE id = ?
          """.trimIndent(),
          readList.name,
          readList.summary,
          readList.ordered.toSqliteInt(),
          readList.updatedAtMillis,
          readList.id.value,
        )
      if (affected == 0) throw NoSuchElementException("Read-list not found")
      transaction.replaceReadListMembers(readList)
    }
  }

  override fun delete(id: ReadListId) {
    database.dsl.execute("DELETE FROM read_list WHERE id = ?", id.value)
  }

  private fun hydrate(records: List<Record>): List<ReadList> {
    val ids = records.map { ReadListId(it.requiredString("id")) }
    val members = loadMembers(ids)
    return records.map { record ->
      val id = ReadListId(record.requiredString("id"))
      record.toReadList(members[id].orEmpty())
    }
  }

  private fun loadMembers(ids: Collection<ReadListId>): Map<ReadListId, List<BookId>> {
    if (ids.isEmpty()) return emptyMap()
    val values = ids.map(ReadListId::value)
    val placeholders = values.joinToString(",") { "?" }
    return database.dsl
      .fetch(
        """
        SELECT read_list_id, book_id
        FROM read_list_member
        WHERE read_list_id IN ($placeholders)
        ORDER BY read_list_id, position
        """.trimIndent(),
        *values.toTypedArray(),
      ).groupBy(
        keySelector = { ReadListId(it.requiredString("read_list_id")) },
        valueTransform = { BookId(it.requiredString("book_id")) },
      )
  }

  private fun DSLContext.replaceReadListMembers(readList: ReadList) {
    execute("DELETE FROM read_list_member WHERE read_list_id = ?", readList.id.value)
    readList.bookIds.forEachIndexed { position, bookId ->
      execute(
        """
        INSERT INTO read_list_member (read_list_id, book_id, position)
        VALUES (?, ?, ?)
        """.trimIndent(),
        readList.id.value,
        bookId.value,
        position,
      )
    }
  }

  private fun Record.toReadList(bookIds: List<BookId>): ReadList =
    ReadList(
      id = ReadListId(requiredString("id")),
      name = requiredString("name"),
      summary = requiredString("summary"),
      ordered = requiredBoolean("ordered"),
      bookIds = bookIds,
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private companion object {
    const val SELECT_READ_LIST =
      """
      SELECT read_list.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM read_list
      """
  }
}

private fun Record.requiredString(field: String): String =
  requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

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
