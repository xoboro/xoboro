package io.xoboro.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaTest {
  @Test
  fun `models Komga compatible ready media`() {
    val media =
      BookMedia(
        bookId = BookId("book-1"),
        status = MediaStatus.READY,
        mediaType = "application/zip",
        profile = MediaProfile.DIVINA,
        pages =
          listOf(
            BookPage(
              number = 1,
              fileName = "001.jpg",
              mediaType = "image/jpeg",
              fileSize = 10,
              dimension = Dimension(800, 1200),
            ),
          ),
        files = listOf(MediaFile("ComicInfo.xml", "application/xml", 20)),
        createdAtMillis = 1,
      )

    assertEquals(1, media.pageCount)
    assertEquals(MediaStatus.READY, media.status)
  }

  @Test
  fun `rejects invalid page and media state`() {
    assertFailsWith<IllegalArgumentException> {
      BookPage(number = 0, fileName = "page.jpg", mediaType = "image/jpeg")
    }
    assertFailsWith<IllegalArgumentException> {
      BookMedia(
        bookId = BookId("book-1"),
        pages =
          listOf(
            BookPage(number = 2, fileName = "page.jpg", mediaType = "image/jpeg"),
          ),
        createdAtMillis = 1,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      BookMedia(
        bookId = BookId("book-1"),
        pageCount = 0,
        pages =
          listOf(
            BookPage(number = 1, fileName = "page.jpg", mediaType = "image/jpeg"),
          ),
        createdAtMillis = 1,
      )
    }
  }
}
