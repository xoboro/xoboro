package io.xoboro.server.media

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalFontResourceCatalogTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `discovers supported font families and builds deterministic CSS`() {
    val family = Files.createDirectories(temporaryDirectory.resolve("Synthetic Sans"))
    Files.write(family.resolve("Regular.woff2"), byteArrayOf(1, 2))
    Files.write(family.resolve("BoldItalic.ttf"), byteArrayOf(3, 4))
    Files.writeString(family.resolve("ignored.txt"), "ignored")

    val catalog = LocalFontResourceCatalog(temporaryDirectory)

    assertEquals(setOf("Synthetic Sans"), catalog.families())
    assertEquals(
      listOf<Byte>(1, 2),
      catalog.resource("Synthetic Sans", "Regular.woff2")?.bytes?.toList(),
    )
    assertNull(catalog.resource("Synthetic Sans", "../Regular.woff2"))
    val css = requireNotNull(catalog.css("Synthetic Sans"))
    assertTrue(css.contains("font-family: 'Synthetic Sans'"))
    assertTrue(css.contains("font-weight: bold"))
    assertTrue(css.contains("font-style: italic"))
    assertTrue(css.contains("format('woff2')"))
  }
}
