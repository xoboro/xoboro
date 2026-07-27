package io.xoboro.compatibility.komga.api

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaRoutesTest {
  @Test
  fun `formats Komga UTF-8 content disposition`() {
    assertEquals(
      "attachment; filename=\"=?UTF-8?Q?001.cbz?=\"; filename*=UTF-8''001.cbz",
      komgaContentDisposition("attachment", "001.cbz"),
    )
    assertEquals(
      "inline; filename=\"=?UTF-8?Q?Synthetic_Page.jpg?=\"; " +
        "filename*=UTF-8''Synthetic%20Page.jpg",
      komgaContentDisposition("inline", "Synthetic Page.jpg"),
    )
  }
}
