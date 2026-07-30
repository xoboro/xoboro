package io.xoboro.server.metadata

import io.xoboro.core.application.SourceSidecarAccess
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesStatus
import io.xoboro.core.domain.SourceLocation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    // booktype has no field of its own and is a short descriptor of the edition, which is what a tag is.
    assertEquals(setOf("Webtoon"), patch.tags)
  }

  @Test
  fun `reads the imprint as a tag without displacing the publisher`() {
    val provider = provider(seriesJson(extra = IMPRINT_FIELD))

    val patch = requireNotNull(provider.provide(library(), series(), emptyList()))

    // An imprint is a division of the publisher. Overwriting `publisher` with it would lose the
    // publisher rather than add the imprint.
    assertEquals("Synthetic publisher", patch.publisher)
    assertEquals(setOf("Webtoon", "Synthetic imprint"), patch.tags)
  }

  @Test
  fun `reports an unreadable sidecar`() {
    val reported = mutableListOf<MylarSeriesDiagnostic>()
    val provider =
      MylarSeriesMetadataProvider(
        accesses = listOf(ThrowingSidecarAccess()),
        diagnostics = reported::add,
      )

    assertNull(provider.provide(library(), series(), emptyList()))

    // A missing sidecar is silent; one that exists and cannot be read is a permission or size problem
    // the operator can act on.
    val diagnostic = assertIs<MylarSeriesDiagnostic.SeriesJsonUnreadable>(reported.single())
    assertEquals("series-item", diagnostic.seriesItemId)
  }

  @Test
  fun `distinguishes malformed JSON from a file that is not Mylar`() {
    val malformed = mutableListOf<MylarSeriesDiagnostic>()
    assertNull(provider("{", malformed::add).provide(library(), series(), emptyList()))
    // A malformed file needs rewriting; the next case is the wrong kind of file under the right name.
    assertIs<MylarSeriesDiagnostic.SeriesJsonMalformed>(malformed.single())

    val notMylar = mutableListOf<MylarSeriesDiagnostic>()
    assertNull(provider(NOT_MYLAR_JSON, notMylar::add).provide(library(), series(), emptyList()))
    assertIs<MylarSeriesDiagnostic.SeriesJsonNotMylar>(notMylar.single())
  }

  @Test
  fun `reports a Mylar file with no usable name`() {
    val reported = mutableListOf<MylarSeriesDiagnostic>()

    assertNull(provider(NAMELESS_JSON, reported::add).provide(library(), series(), emptyList()))

    // The one genuinely required field: a patch with no title would overwrite nothing while reporting
    // an import.
    assertIs<MylarSeriesDiagnostic.SeriesJsonMissingName>(reported.single())
  }

  @Test
  fun `imports what it can and reports the fields it could not use`() {
    val reported = mutableListOf<MylarSeriesDiagnostic>()

    val patch =
      requireNotNull(
        provider(UNUSABLE_FIELDS_JSON, reported::add).provide(library(), series(), emptyList()),
      )

    // One unparsable field must not cost the operator their title and publisher.
    assertEquals("Synthetic series", patch.title)
    assertEquals("Synthetic publisher", patch.publisher)
    assertNull(patch.status)
    assertNull(patch.ageRating)
    assertNull(patch.totalBookCount)
    assertEquals(
      setOf("status" to "Hiatus", "age_rating" to "unrated", "total_issues" to "many"),
      reported
        .filterIsInstance<MylarSeriesDiagnostic.SeriesJsonFieldIgnored>()
        .map { it.field to it.value }
        .toSet(),
    )
  }

  @Test
  fun `reports schema drift without reporting fields it knowingly skips`() {
    val reported = mutableListOf<MylarSeriesDiagnostic>()

    requireNotNull(
      provider(DRIFTING_JSON, reported::add).provide(library(), series(), emptyList()),
    )

    // Only the genuinely unrecognised name. Reporting the knowingly-skipped ones would fire on every
    // well-formed Mylar file and train an operator to ignore the diagnostic that matters.
    val drift = assertIs<MylarSeriesDiagnostic.SeriesJsonIgnored>(reported.single())
    assertEquals(setOf("future_field"), drift.fields)
  }

  @Test
  fun `reports nothing for a well-formed file`() {
    val reported = mutableListOf<MylarSeriesDiagnostic>()

    requireNotNull(provider(seriesJson(), reported::add).provide(library(), series(), emptyList()))

    assertEquals(emptyList(), reported)
  }

  private fun provider(
    content: String?,
    diagnostics: (MylarSeriesDiagnostic) -> Unit = { },
  ): MylarSeriesMetadataProvider =
    MylarSeriesMetadataProvider(
      accesses = listOf(FixedSidecarAccess(content)),
      diagnostics = diagnostics,
    )

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

  private fun seriesJson(extra: String = ""): String =
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
        "status": "Continuing"$extra
      }
    }
    """.trimIndent()

  private class ThrowingSidecarAccess : SourceSidecarAccess {
    override val sourceId: String = "synthetic"

    override fun readSeriesSidecar(
      rootItemId: String,
      seriesItemId: String,
      fileName: String,
      maximumBytes: Int,
    ): ByteArray = throw IOException("permission denied")
  }

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

  private companion object {
    const val IMPRINT_FIELD = ""","imprint": "Synthetic imprint""""

    const val NOT_MYLAR_JSON = """{"other":1}"""

    const val NAMELESS_JSON = """{"metadata":{"publisher":"Synthetic publisher"}}"""

    val UNUSABLE_FIELDS_JSON =
      """
      {
        "metadata": {
          "name": "Synthetic series",
          "publisher": "Synthetic publisher",
          "status": "Hiatus",
          "age_rating": "unrated",
          "total_issues": "many"
        }
      }
      """.trimIndent()

    val DRIFTING_JSON =
      """
      {
        "metadata": {
          "name": "Synthetic series",
          "comicid": 1234,
          "collects": "Issues 1-6",
          "publication_run": "2020 - 2021",
          "comic_image": "https://example.invalid/cover.jpg",
          "type": "comicSeries",
          "future_field": "value"
        }
      }
      """.trimIndent()
  }
}
