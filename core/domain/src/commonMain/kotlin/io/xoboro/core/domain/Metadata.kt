package io.xoboro.core.domain

data class Author(
  val name: String,
  val role: String,
) {
  init {
    require(name.isNotBlank()) { "Author name must not be blank" }
    require(role.isNotBlank()) { "Author role must not be blank" }
  }

  val normalizedName: String = name.trim()
  val normalizedRole: String = role.trim().lowercase()
}

data class WebLink(
  val label: String,
  val url: String,
) {
  init {
    require(label.isNotBlank()) { "Web link label must not be blank" }
    require(url.isNotBlank()) { "Web link URL must not be blank" }
  }
}

data class AlternateTitle(
  val label: String,
  val title: String,
) {
  init {
    require(label.isNotBlank()) { "Alternate title label must not be blank" }
    require(title.isNotBlank()) { "Alternate title must not be blank" }
  }
}

data class BookMetadata(
  val bookId: BookId,
  val title: String,
  val summary: String = "",
  val number: String,
  val numberSort: Float,
  val releaseDate: String? = null,
  val authors: List<Author> = emptyList(),
  val tags: Set<String> = emptySet(),
  val isbn: String = "",
  val links: List<WebLink> = emptyList(),
  val titleLock: Boolean = false,
  val summaryLock: Boolean = false,
  val numberLock: Boolean = false,
  val numberSortLock: Boolean = false,
  val releaseDateLock: Boolean = false,
  val authorsLock: Boolean = false,
  val tagsLock: Boolean = false,
  val isbnLock: Boolean = false,
  val linksLock: Boolean = false,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(numberSort.isFinite()) { "Book metadata sort number must be finite" }
    require(releaseDate == null || ISO_DATE.matches(releaseDate)) {
      "Book metadata release date must use YYYY-MM-DD"
    }
    require(createdAtMillis >= 0) { "Metadata creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Metadata update timestamp must not precede creation"
    }
  }

  val normalizedTitle: String = title.trim()
  val normalizedSummary: String = summary.trim()
  val normalizedNumber: String = number.trim()
  val normalizedTags: Set<String> =
    tags.asSequence().map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet()

  companion object {
    private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
  }
}

enum class SeriesStatus {
  ENDED,
  ONGOING,
  ABANDONED,
  HIATUS,
}

enum class ReadingDirection {
  LEFT_TO_RIGHT,
  RIGHT_TO_LEFT,
  VERTICAL,
  WEBTOON,
}

data class SeriesMetadata(
  val seriesId: SeriesId,
  val status: SeriesStatus = SeriesStatus.ONGOING,
  val title: String,
  val titleSort: String = title,
  val summary: String = "",
  val readingDirection: ReadingDirection? = null,
  val publisher: String = "",
  val ageRating: Int? = null,
  val language: String = "",
  val genres: Set<String> = emptySet(),
  val tags: Set<String> = emptySet(),
  val totalBookCount: Int? = null,
  val sharingLabels: Set<String> = emptySet(),
  val links: List<WebLink> = emptyList(),
  val alternateTitles: List<AlternateTitle> = emptyList(),
  val statusLock: Boolean = false,
  val titleLock: Boolean = false,
  val titleSortLock: Boolean = false,
  val summaryLock: Boolean = false,
  val readingDirectionLock: Boolean = false,
  val publisherLock: Boolean = false,
  val ageRatingLock: Boolean = false,
  val languageLock: Boolean = false,
  val genresLock: Boolean = false,
  val tagsLock: Boolean = false,
  val totalBookCountLock: Boolean = false,
  val sharingLabelsLock: Boolean = false,
  val linksLock: Boolean = false,
  val alternateTitlesLock: Boolean = false,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(ageRating == null || ageRating >= 0) {
      "Series metadata age rating must not be negative"
    }
    require(totalBookCount == null || totalBookCount >= 0) {
      "Series metadata total book count must not be negative"
    }
    require(createdAtMillis >= 0) { "Metadata creation timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Metadata update timestamp must not precede creation"
    }
  }

  val normalizedTitle: String = title.trim()
  val normalizedTitleSort: String = titleSort.trim()
  val normalizedSummary: String = summary.trim()
  val normalizedPublisher: String = publisher.trim()
  val normalizedLanguage: String = language.trim()
  val normalizedGenres: Set<String> = genres.normalizedValues()
  val normalizedTags: Set<String> = tags.normalizedValues()
  val normalizedSharingLabels: Set<String> = sharingLabels.normalizedValues()

  private fun Set<String>.normalizedValues(): Set<String> =
    asSequence().map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet()
}

interface BookMetadataRepository {
  fun findByBookIdOrNull(bookId: BookId): BookMetadata?

  fun upsert(metadata: BookMetadata)
}

interface SeriesMetadataRepository {
  fun findBySeriesIdOrNull(seriesId: SeriesId): SeriesMetadata?

  fun upsert(metadata: SeriesMetadata)
}
