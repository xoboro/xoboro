package io.xoboro.server.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Sha512TokenEncoderTest {
  private val encoder = Sha512TokenEncoder()

  @Test
  fun `matches the deterministic lowercase Komga SHA-512 representation`() {
    assertEquals(
      "12fbc5cfdb2d3b868c866b45168eeba2748a65f157f33ceaee56d62ecbd65c20f" +
        "b321003ec99c010c50b1114bf2d79e7b81a85127397fc07ab055883e3fb6353",
      encoder.encode("synthetic-token"),
    )
    assertEquals(128, encoder.encode("synthetic-token").length)
    assertNotEquals(encoder.encode("synthetic-token"), encoder.encode("other-token"))
  }
}
