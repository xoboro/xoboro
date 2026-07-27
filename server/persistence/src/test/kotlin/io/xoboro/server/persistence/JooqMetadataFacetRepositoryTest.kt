package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogAccess
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.ManualBookMetadataPatch
import io.xoboro.core.application.ManualSeriesMetadataPatch
import io.xoboro.core.application.MetadataEditingLifecycle
import io.xoboro.core.application.MetadataFacet
import io.xoboro.core.application.MetadataFacetQuery
import io.xoboro.core.application.PatchField
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.Author
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqMetadataFacetRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `manual edits distinguish omitted fields from explicit nulls`() {
    withCatalog("editing") { database ->
      val books = JooqBookRepository(database)
      val series = JooqSeriesRepository(database)
      val bookMetadata = JooqBookMetadataRepository(database)
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      val lifecycle =
        MetadataEditingLifecycle(
          books,
          series,
          bookMetadata,
          seriesMetadata,
          currentTimeMillis = { 50 },
        )
      bookMetadata.upsert(
        requireNotNull(bookMetadata.findByBookIdOrNull(BOOK_A)).copy(
          summary = "Keep summary",
          releaseDate = "2026-01-02",
          tags = setOf("old"),
          updatedAtMillis = 20,
        ),
      )
      seriesMetadata.upsert(
        requireNotNull(seriesMetadata.findBySeriesIdOrNull(SERIES_A)).copy(
          ageRating = 15,
          genres = setOf("old genre"),
          updatedAtMillis = 20,
        ),
      )

      val updatedBook =
        lifecycle.patchBook(
          BOOK_A,
          ManualBookMetadataPatch(
            releaseDate = PatchField(present = true, value = null),
            tags = PatchField(present = true, value = setOf("new")),
            summaryLock = true,
          ),
        )
      val updatedSeries =
        lifecycle.patchSeries(
          SERIES_A,
          ManualSeriesMetadataPatch(
            ageRating = PatchField(present = true, value = null),
            genres = PatchField(present = true, value = emptySet()),
          ),
        )

      assertEquals("Keep summary", updatedBook.summary)
      assertEquals(true, updatedBook.summaryLock)
      assertNull(updatedBook.releaseDate)
      assertEquals(setOf("new"), updatedBook.tags)
      assertNull(updatedSeries.ageRating)
      assertEquals(emptySet(), updatedSeries.genres)
      assertEquals(50, updatedSeries.updatedAtMillis)
    }
  }

  @Test
  fun `facets apply authorization and content restrictions before distinct values`() {
    withCatalog("facets") { database ->
      val seriesMetadata = JooqSeriesMetadataRepository(database)
      val bookMetadata = JooqBookMetadataRepository(database)
      seriesMetadata.upsert(
        requireNotNull(seriesMetadata.findBySeriesIdOrNull(SERIES_A)).copy(
          publisher = "Allowed publisher",
          language = "en",
          genres = setOf("adventure"),
          tags = setOf("visible-series"),
          sharingLabels = setOf("family"),
          ageRating = 10,
          updatedAtMillis = 20,
        ),
      )
      seriesMetadata.upsert(
        requireNotNull(seriesMetadata.findBySeriesIdOrNull(SERIES_B)).copy(
          publisher = "Blocked publisher",
          language = "fr",
          genres = setOf("restricted"),
          tags = setOf("hidden-series"),
          sharingLabels = setOf("restricted"),
          ageRating = 18,
          updatedAtMillis = 20,
        ),
      )
      bookMetadata.upsert(
        requireNotNull(bookMetadata.findByBookIdOrNull(BOOK_A)).copy(
          releaseDate = "2026-01-02",
          authors = listOf(Author("Synthetic Author", "writer")),
          tags = setOf("visible-book"),
          updatedAtMillis = 20,
        ),
      )
      bookMetadata.upsert(
        requireNotNull(bookMetadata.findByBookIdOrNull(BOOK_B)).copy(
          releaseDate = "2025-01-02",
          authors = listOf(Author("Hidden Author", "writer")),
          tags = setOf("hidden-book"),
          updatedAtMillis = 20,
        ),
      )
      val repository = JooqMetadataFacetRepository(database)
      val access =
        CatalogAccess(
          libraryIds = setOf(LIBRARY_A),
          restrictions =
            ContentRestrictions(
              ageRestriction = AgeRestriction(15, RestrictionMode.EXCLUDE),
              labelsExclude = setOf("restricted"),
            ),
        )

      assertEquals(
        listOf("adventure"),
        repository.findValues(MetadataFacet.GENRE, MetadataFacetQuery(), access),
      )
      assertEquals(
        listOf("Allowed publisher"),
        repository.findValues(MetadataFacet.PUBLISHER, MetadataFacetQuery(), access),
      )
      assertEquals(
        listOf("2026"),
        repository.findValues(MetadataFacet.RELEASE_YEAR, MetadataFacetQuery(), access),
      )
      val authors =
        repository.findAuthors(
          MetadataFacetQuery(search = "synthetic", role = "writer"),
          access,
          CatalogPageRequest(size = 1),
        )
      assertEquals(1, authors.totalElements)
      assertEquals(listOf("Synthetic Author"), authors.content.map(Author::normalizedName))
    }
  }

  private fun withCatalog(
    name: String,
    block: (XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      val libraries = JooqLibraryRepository(database)
      val series = JooqSeriesRepository(database)
      val books = JooqBookRepository(database)
      listOf(LIBRARY_A, LIBRARY_B).forEachIndexed { index, id ->
        libraries.insert(
          Library(
            id = id,
            name = "Synthetic library $index",
            root = SourceLocation("local", "file:///synthetic-$index"),
            createdAtMillis = 1,
          ),
        )
      }
      listOf(
        Triple(SERIES_A, LIBRARY_A, BOOK_A),
        Triple(SERIES_B, LIBRARY_B, BOOK_B),
      ).forEachIndexed { index, (seriesId, libraryId, bookId) ->
        series.insert(
          Series(
            id = seriesId,
            libraryId = libraryId,
            name = "Synthetic series $index",
            relativePath = "series-$index",
            sourceItemId = "file:///synthetic-$index/series",
            fileModifiedAtMillis = 1,
            bookCount = 1,
            createdAtMillis = 1,
          ),
        )
        books.insert(
          Book(
            id = bookId,
            libraryId = libraryId,
            seriesId = seriesId,
            name = "Synthetic book $index",
            relativePath = "series-$index/book.cbz",
            sourceItemId = "file:///synthetic-$index/series/book.cbz",
            mediaKind = MediaKind.COMIC_ARCHIVE,
            fileModifiedAtMillis = 1,
            number = 1,
            createdAtMillis = 1,
          ),
        )
      }
      block(database)
    }
  }

  private companion object {
    val LIBRARY_A = LibraryId("library-a")
    val LIBRARY_B = LibraryId("library-b")
    val SERIES_A = SeriesId("series-a")
    val SERIES_B = SeriesId("series-b")
    val BOOK_A = BookId("book-a")
    val BOOK_B = BookId("book-b")
  }
}
