package io.xoboro.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RarVolumeNamesTest {
  @Test
  fun `parses the tool-generated volume scheme`() {
    assertEquals(
      RarVolumeName(stem = "Synthetic Series 01.rar", number = 1),
      RarVolumeNames.parseOrNull("Synthetic Series 01.part1.rar"),
    )
    assertEquals(
      RarVolumeName(stem = "Synthetic Series 01.rar", number = 2),
      RarVolumeNames.parseOrNull("Synthetic Series 01.part2.rar"),
    )
    assertEquals(
      RarVolumeName(stem = "Synthetic Series 01.cbr", number = 3),
      RarVolumeNames.parseOrNull("Synthetic Series 01.part3.cbr"),
    )
  }

  @Test
  fun `treats a zero-padded number as the same volume as its unpadded form`() {
    // A set of ten or more volumes is padded. `part01` and `part1` are the same position, so the
    // numeric value has to be what identifies it - comparing the text would split one set in two.
    assertEquals(1, RarVolumeNames.parseOrNull("Synthetic.part01.rar")?.number)
    assertEquals(1, RarVolumeNames.parseOrNull("Synthetic.part001.rar")?.number)
    assertEquals(12, RarVolumeNames.parseOrNull("Synthetic.part012.rar")?.number)
  }

  @Test
  fun `groups volumes of one set under a single stem`() {
    val stems =
      listOf("Synthetic.part1.rar", "Synthetic.part02.rar", "Synthetic.part3.rar")
        .mapNotNull(RarVolumeNames::parseOrNull)
        .map(RarVolumeName::stem)
        .distinct()

    assertEquals(listOf("Synthetic.rar"), stems)
  }

  @Test
  fun `does not treat a book titled part N as a volume`() {
    // These are whole books whose titles happen to contain the word. Matching them would make a real
    // book vanish from the catalog, which is worse than leaving a broken volume visible.
    assertNull(RarVolumeNames.parseOrNull("Series - part 2.cbr"))
    assertNull(RarVolumeNames.parseOrNull("Series part 2.cbr"))
    assertNull(RarVolumeNames.parseOrNull("Series.part 2.cbr"))
    assertNull(RarVolumeNames.parseOrNull("Series_part2.cbr"))
    assertNull(RarVolumeNames.parseOrNull("Series-part2.cbr"))
    assertNull(RarVolumeNames.parseOrNull("Series..part2.cbr"))
  }

  @Test
  fun `does not recognise extensions that are not RAR archives`() {
    assertNull(RarVolumeNames.parseOrNull("Synthetic.part2.cbz"))
    assertNull(RarVolumeNames.parseOrNull("Synthetic.part2.zip"))
    assertNull(RarVolumeNames.parseOrNull("Synthetic.part2.pdf"))
    // The RAR 2 continuation scheme is not recognised here because those extensions are never
    // scanned, so such files never become candidates in the first place.
    assertNull(RarVolumeNames.parseOrNull("Synthetic.r00"))
    assertNull(RarVolumeNames.parseOrNull("Synthetic.s01"))
  }

  @Test
  fun `rejects malformed volume numbers`() {
    assertNull(RarVolumeNames.parseOrNull("Synthetic.part.rar"))
    assertNull(RarVolumeNames.parseOrNull("Synthetic.part0.rar"))
    assertNull(RarVolumeNames.parseOrNull("Synthetic.partx.rar"))
    assertNull(RarVolumeNames.parseOrNull("Synthetic.part1234.rar"))
    assertNull(RarVolumeNames.parseOrNull(".part1.rar"))
  }

  @Test
  fun `is case insensitive on both the marker and the extension`() {
    assertEquals(2, RarVolumeNames.parseOrNull("Synthetic.PART2.RAR")?.number)
    assertEquals(2, RarVolumeNames.parseOrNull("Synthetic.Part2.Cbr")?.number)
  }

  @Test
  fun `keeps sets with different extensions apart`() {
    // No tool produces a set that mixes extensions, so treating these as one set would only let an
    // oddly named file suppress an unrelated one.
    assertEquals(
      listOf("Synthetic.rar", "Synthetic.cbr"),
      listOf("Synthetic.part1.rar", "Synthetic.part2.cbr")
        .mapNotNull(RarVolumeNames::parseOrNull)
        .map(RarVolumeName::stem),
    )
  }
}
