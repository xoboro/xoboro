package io.xoboro.core.application

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The naming rules both archive surfaces share.
 *
 * The route tests reach these through a response, which covers one collision and one unnamed member.
 * What they cannot reach is a *third* member colliding with the same name - a plausible shape for a
 * series whose volumes were filed in per-language directories - so the disambiguation loop is
 * exercised here instead.
 */
class MediaArchiveNamingTest {
  @Test
  fun `download name replaces characters an extractor would read as structure`() {
    assertEquals(
      "Synthetic_ set_volume _one_.zip",
      mediaArchiveFileName("Synthetic: set/volume <one>"),
    )
    // A control character becomes an underscore rather than vanishing, so the name still says
    // something was there.
    assertEquals("_.zip", mediaArchiveFileName("\u0000"))
    // A title that sanitizes away to nothing falls back to a usable name - above all not ".zip", a
    // hidden file on every Unix desktop, which is what appending the extension before sanitizing
    // produced while that order was the Komga-compatible route's.
    assertEquals("archive.zip", mediaArchiveFileName("   "))
    assertEquals("archive.zip", mediaArchiveFileName(""))
  }

  @Test
  fun `entry name keeps the leaf only and prefixes an ordered member`() {
    assertEquals(
      "synthetic-one.cbz",
      mediaArchiveEntryName("/library/set/synthetic-one.cbz", "fallback.bin", entryPrefix = null),
    )
    assertEquals(
      "synthetic-one.cbz",
      mediaArchiveEntryName("C:\\library\\set\\synthetic-one.cbz", "fallback.bin", entryPrefix = null),
    )
    assertEquals(
      "7 - synthetic-one.cbz",
      mediaArchiveEntryName("/library/set/synthetic-one.cbz", "fallback.bin", entryPrefix = 7),
    )
    assertEquals(
      "fallback.bin",
      mediaArchiveEntryName(null, "fallback.bin", entryPrefix = null),
    )
  }

  @Test
  fun `each further collision takes the next index rather than repeating one suffix`() {
    val used = mutableSetOf<String>()
    val names =
      List(4) { "synthetic-one.cbz".uniqueArchiveName(used) } +
        "no-extension".uniqueArchiveName(used) +
        "no-extension".uniqueArchiveName(used)

    assertEquals(
      listOf(
        "synthetic-one.cbz",
        "synthetic-one (2).cbz",
        "synthetic-one (3).cbz",
        "synthetic-one (4).cbz",
        "no-extension",
        "no-extension (2)",
      ),
      names,
    )
    // Four distinct names went in, so nothing above was satisfied by two entries sharing one.
    assertEquals(names.size, names.toSet().size)
  }
}
