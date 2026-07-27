package io.xoboro.server.metadata

import io.xoboro.core.application.SourceSidecarAccess
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MylarSeriesMetadataProviderTest {
  @Test
  fun `imports Mylar series json including description text aliases`() {
    val provider =
      MylarSeriesMetadataProvider(
        listOf(
          FixedSidecarAccess(
            """
            {
              "version": "1.0.2",
              "metadata": {
                "type": "comicSeries",
                "publisher": "Synthetic publisher",
                "name": "Synthetic series",
                "comicid": 1234,
                "year": 2026,
                "description_text": "Synthetic plain summary",
                "description_formatted": "Synthetic formatted summary",
                "volume": 2,
                "booktype": "Webtoon",
                "age_rating": "15+",
                "total_issues": 42,
                "status": "Continuing"
              }
            }
            """.trimIndent(),
          ),
        ),
      )

    val patch = requireNotNull(provider.provide(library(), series(), emptyList()))

    assertEquals("Synthetic series (2026)", patch.title)
    assertEquals("Synthetic formatted summary", patch.summary)
    assertEquals("Synthetic publisher", patch.publisher)
    assertEquals(15, patch.ageRating)
    assertEquals(42, patch.totalBookCount)
    assertEquals(SeriesStatus.ONGOING, patch.status)
  }

  @Test
  fun `skips missing malformed disabled and oneshot sidecars`() {
    assertNull(
      MylarSeriesMetadataProvider(listOf(FixedSidecarAccess(null)))
        .provide(library(), series(), emptyList()),
    )
    assertNull(
      MylarSeriesMetadataProvider(listOf(FixedSidecarAccess("{")))
        .provide(library(), series(), emptyList()),
    )
    assertNull(
      MylarSeriesMetadataProvider(listOf(FixedSidecarAccess("{}")))
        .provide(
          library().copy(
            settings = library().settings.copy(importMylarSeries = false),
          ),
          series(),
          emptyList(),
        ),
    )
    assertNull(
      MylarSeriesMetadataProvider(listOf(FixedSidecarAccess("{}")))
        .provide(library(), series().copy(oneshot = true), emptyList()),
    )
  }

  private fun library(): Library =
    Library(
      id = LibraryId("library-1"),
      name = "Synthetic library",
      root = SourceLocation("synthetic", "root"),
      createdAtMillis = 1,
    )

  private fun series(): Series =
    Series(
      id = SeriesId("series-1"),
      libraryId = LibraryId("library-1"),
      name = "Synthetic series",
      relativePath = "series",
      sourceItemId = "series-item",
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private class FixedSidecarAccess(
    private val content: String?,
  ) : SourceSidecarAccess {
    override val sourceId: String = "synthetic"

    override fun readSeriesSidecar(
      rootItemId: String,
      seriesItemId: String,
      fileName: String,
      maximumBytes: Int,
    ): ByteArray? = content?.encodeToByteArray()
  }
}
