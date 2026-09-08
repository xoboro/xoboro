package io.xoboro.core.application

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaDeliveryTest {
  @Test
  fun `positional raw page request remains source compatible`() {
    assertEquals(
      PageImageRequest(raw = true),
      PageImageRequest(null, null, true),
    )
  }
}
