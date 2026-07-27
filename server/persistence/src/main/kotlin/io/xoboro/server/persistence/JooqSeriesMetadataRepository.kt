package io.xoboro.server.persistence

import io.xoboro.core.domain.AlternateTitle
import io.xoboro.core.domain.ReadingDirection
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SeriesMetadataRepository
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.WebLink
import org.jooq.DSLContext
import org.jooq.Record

class JooqSeriesMetadataRepository(
  private val database: XoboroDatabase,
) : SeriesMetadataRepository {
  override fun findBySeriesIdOrNull(seriesId: SeriesId): SeriesMetadata? {
    val record =
      database.dsl
        .fetchOne(
          """
          SELECT series_metadata.*,
            CAST(created_at_ms AS TEXT) AS created_at_ms_64,
            CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
          FROM series_metadata
          WHERE series_id = ?
          """.trimIndent(),
          seriesId.value,
        )
        ?: return null
    return record.toMetadata(
      genres = loadValues("series_metadata_genre", "genre", seriesId),
      tags = loadValues("series_metadata_tag", "tag", seriesId),
      sharingLabels =
        loadValues(
          "series_metadata_sharing_label",
          "sharing_label",
          seriesId,
        ),
      links = loadLinks(seriesId),
      alternateTitles = loadAlternateTitles(seriesId),
    )
  }

  override fun upsert(metadata: SeriesMetadata) {
    database.transaction { transaction ->
      transaction.execute(
        """
        INSERT INTO series_metadata (
          series_id, status, title, title_sort, summary, reading_direction,
          publisher, age_rating, language, total_book_count,
          status_lock, title_lock, title_sort_lock, summary_lock,
          reading_direction_lock, publisher_lock, age_rating_lock, language_lock,
          genres_lock, tags_lock, total_book_count_lock, sharing_labels_lock,
          links_lock, alternate_titles_lock, created_at_ms, updated_at_ms
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        ON CONFLICT(series_id) DO UPDATE SET
          status = excluded.status,
          title = excluded.title,
          title_sort = excluded.title_sort,
          summary = excluded.summary,
          reading_direction = excluded.reading_direction,
          publisher = excluded.publisher,
          age_rating = excluded.age_rating,
          language = excluded.language,
          total_book_count = excluded.total_book_count,
          status_lock = excluded.status_lock,
          title_lock = excluded.title_lock,
          title_sort_lock = excluded.title_sort_lock,
          summary_lock = excluded.summary_lock,
          reading_direction_lock = excluded.reading_direction_lock,
          publisher_lock = excluded.publisher_lock,
          age_rating_lock = excluded.age_rating_lock,
          language_lock = excluded.language_lock,
          genres_lock = excluded.genres_lock,
          tags_lock = excluded.tags_lock,
          total_book_count_lock = excluded.total_book_count_lock,
          sharing_labels_lock = excluded.sharing_labels_lock,
          links_lock = excluded.links_lock,
          alternate_titles_lock = excluded.alternate_titles_lock,
          created_at_ms = excluded.created_at_ms,
          updated_at_ms = excluded.updated_at_ms
        """.trimIndent(),
        metadata.seriesId.value,
        metadata.status.name,
        metadata.normalizedTitle,
        metadata.normalizedTitleSort,
        metadata.normalizedSummary,
        metadata.readingDirection?.name,
        metadata.normalizedPublisher,
        metadata.ageRating,
        metadata.normalizedLanguage,
        metadata.totalBookCount,
        metadata.statusLock.toSqliteInt(),
        metadata.titleLock.toSqliteInt(),
        metadata.titleSortLock.toSqliteInt(),
        metadata.summaryLock.toSqliteInt(),
        metadata.readingDirectionLock.toSqliteInt(),
        metadata.publisherLock.toSqliteInt(),
        metadata.ageRatingLock.toSqliteInt(),
        metadata.languageLock.toSqliteInt(),
        metadata.genresLock.toSqliteInt(),
        metadata.tagsLock.toSqliteInt(),
        metadata.totalBookCountLock.toSqliteInt(),
        metadata.sharingLabelsLock.toSqliteInt(),
        metadata.linksLock.toSqliteInt(),
        metadata.alternateTitlesLock.toSqliteInt(),
        metadata.createdAtMillis,
        metadata.updatedAtMillis,
      )
      transaction.replaceValues(
        table = "series_metadata_genre",
        column = "genre",
        metadata = metadata,
        values = metadata.normalizedGenres,
      )
      transaction.replaceValues(
        table = "series_metadata_tag",
        column = "tag",
        metadata = metadata,
        values = metadata.normalizedTags,
      )
      transaction.replaceValues(
        table = "series_metadata_sharing_label",
        column = "sharing_label",
        metadata = metadata,
        values = metadata.normalizedSharingLabels,
      )
      transaction.replaceLinks(metadata)
      transaction.replaceAlternateTitles(metadata)
    }
  }

  private fun loadValues(
    table: String,
    column: String,
    seriesId: SeriesId,
  ): Set<String> =
    database.dsl
      .fetch(
        "SELECT $column FROM $table WHERE series_id = ? ORDER BY $column",
        seriesId.value,
      ).map { it.requiredString(column) }.toSet()

  private fun loadLinks(seriesId: SeriesId): List<WebLink> =
    database.dsl
      .fetch(
        """
        SELECT label, url
        FROM series_metadata_link
        WHERE series_id = ?
        ORDER BY ordinal
        """.trimIndent(),
        seriesId.value,
      )
      .map { WebLink(it.requiredString("label"), it.requiredString("url")) }

  private fun loadAlternateTitles(seriesId: SeriesId): List<AlternateTitle> =
    database.dsl
      .fetch(
        """
        SELECT label, title
        FROM series_metadata_alternate_title
        WHERE series_id = ?
        ORDER BY ordinal
        """.trimIndent(),
        seriesId.value,
      )
      .map { AlternateTitle(it.requiredString("label"), it.requiredString("title")) }

  private fun DSLContext.replaceValues(
    table: String,
    column: String,
    metadata: SeriesMetadata,
    values: Set<String>,
  ) {
    execute("DELETE FROM $table WHERE series_id = ?", metadata.seriesId.value)
    values.sorted().forEach { value ->
      execute(
        "INSERT INTO $table (series_id, $column) VALUES (?, ?)",
        metadata.seriesId.value,
        value,
      )
    }
  }

  private fun DSLContext.replaceLinks(metadata: SeriesMetadata) {
    execute("DELETE FROM series_metadata_link WHERE series_id = ?", metadata.seriesId.value)
    metadata.links.forEachIndexed { index, link ->
      execute(
        """
        INSERT INTO series_metadata_link (series_id, ordinal, label, url)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
        metadata.seriesId.value,
        index,
        link.label,
        link.url,
      )
    }
  }

  private fun DSLContext.replaceAlternateTitles(metadata: SeriesMetadata) {
    execute(
      "DELETE FROM series_metadata_alternate_title WHERE series_id = ?",
      metadata.seriesId.value,
    )
    metadata.alternateTitles.forEachIndexed { index, alternate ->
      execute(
        """
        INSERT INTO series_metadata_alternate_title
          (series_id, ordinal, label, title)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
        metadata.seriesId.value,
        index,
        alternate.label,
        alternate.title,
      )
    }
  }

  private fun Record.toMetadata(
    genres: Set<String>,
    tags: Set<String>,
    sharingLabels: Set<String>,
    links: List<WebLink>,
    alternateTitles: List<AlternateTitle>,
  ): SeriesMetadata =
    SeriesMetadata(
      seriesId = SeriesId(requiredString("series_id")),
      status = SeriesStatus.valueOf(requiredString("status")),
      title = requiredString("title"),
      titleSort = requiredString("title_sort"),
      summary = requiredString("summary"),
      readingDirection =
        get("reading_direction", String::class.java)?.let(ReadingDirection::valueOf),
      publisher = requiredString("publisher"),
      ageRating = get("age_rating", Int::class.java),
      language = requiredString("language"),
      genres = genres,
      tags = tags,
      totalBookCount = get("total_book_count", Int::class.java),
      sharingLabels = sharingLabels,
      links = links,
      alternateTitles = alternateTitles,
      statusLock = requiredBoolean("status_lock"),
      titleLock = requiredBoolean("title_lock"),
      titleSortLock = requiredBoolean("title_sort_lock"),
      summaryLock = requiredBoolean("summary_lock"),
      readingDirectionLock = requiredBoolean("reading_direction_lock"),
      publisherLock = requiredBoolean("publisher_lock"),
      ageRatingLock = requiredBoolean("age_rating_lock"),
      languageLock = requiredBoolean("language_lock"),
      genresLock = requiredBoolean("genres_lock"),
      tagsLock = requiredBoolean("tags_lock"),
      totalBookCountLock = requiredBoolean("total_book_count_lock"),
      sharingLabelsLock = requiredBoolean("sharing_labels_lock"),
      linksLock = requiredBoolean("links_lock"),
      alternateTitlesLock = requiredBoolean("alternate_titles_lock"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

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
}
