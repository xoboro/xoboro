package io.xoboro.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaItemTest {
  @Test
  fun `classifies Komga formats into extensible media item types`() {
    val comic = syntheticBook(MediaKind.COMIC_ARCHIVE).classifyForLibrary()
    val novel = syntheticBook(MediaKind.EPUB).classifyForLibrary()
    val book = syntheticBook(MediaKind.PDF).classifyForLibrary()

    assertEquals(MediaItemType.COMIC, comic.type)
    assertTrue(comic.supports(MediaCapability.PAGE_SEQUENCE))
    assertTrue(comic.supports(MediaCapability.IMAGE_CONTENT))
    assertEquals(MediaItemType.NOVEL, novel.type)
    assertTrue(novel.supports(MediaCapability.REFLOWABLE_TEXT))
    assertEquals(MediaItemType.BOOK, book.type)
    assertTrue(book.supports(MediaCapability.PAGE_SEQUENCE))
    assertEquals("Synthetic item", comic.name)
  }

  @Test
  fun `models future video and audio as timeline media without a series requirement`() {
    val core = syntheticCore()
    val video = Video(core, durationMillis = 90_000)
    val audio = Audio(core, durationMillis = 180_000)

    assertEquals(MediaItemType.VIDEO, video.type)
    assertTrue(video.supports(MediaCapability.VIDEO_CONTENT))
    assertTrue(video.supports(MediaCapability.AUDIO_CONTENT))
    assertTrue(video.supports(MediaCapability.TIMELINE))
    assertFalse(video.supports(MediaCapability.PAGE_SEQUENCE))
    assertEquals(MediaItemType.AUDIO, audio.type)
    assertTrue(audio.supports(MediaCapability.AUDIO_CONTENT))
    assertFalse(audio.supports(MediaCapability.VIDEO_CONTENT))
    assertFailsWith<IllegalArgumentException> {
      Audio(core, durationMillis = -1)
    }
  }

  private fun syntheticBook(mediaKind: MediaKind): Book =
    Book(
      id = BookId("item-${mediaKind.name.lowercase()}"),
      libraryId = LibraryId("library-1"),
      seriesId = SeriesId("series-1"),
      name = "Synthetic item",
      relativePath = "synthetic/item",
      sourceItemId = "source-item",
      mediaKind = mediaKind,
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )

  private fun syntheticCore(): MediaItemCore =
    MediaItemCore(
      id = MediaItemId("item-1"),
      libraryId = LibraryId("library-1"),
      name = "Synthetic timeline item",
      relativePath = "synthetic/timeline-item",
      sourceItemId = "source-item",
      fileModifiedAtMillis = 1,
      createdAtMillis = 1,
    )
}
