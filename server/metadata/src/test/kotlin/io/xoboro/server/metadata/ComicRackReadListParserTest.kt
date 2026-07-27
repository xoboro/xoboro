package io.xoboro.server.metadata

import io.xoboro.core.application.ReadListImportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComicRackReadListParserTest {
  private val parser = ComicRackReadListParser()

  @Test
  fun `parses attribute and element forms with volume aliases`() {
    val request =
      parser.parse(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <ReadingList>
          <Name>Synthetic reading order</Name>
          <Books>
            <Book Series="Synthetic catalog" Number=" 01 " Volume="2026" />
            <Book>
              <Series>Other synthetic catalog</Series>
              <Number>2</Number>
              <Volume>1</Volume>
            </Book>
          </Books>
        </ReadingList>
        """.trimIndent().encodeToByteArray(),
      )

    assertEquals("Synthetic reading order", request.name)
    assertEquals(
      setOf("Synthetic catalog (2026)", "Synthetic catalog"),
      request.books[0].series,
    )
    assertEquals("01", request.books[0].number)
    assertEquals(setOf("Other synthetic catalog"), request.books[1].series)
    assertEquals("2", request.books[1].number)
  }

  @Test
  fun `rejects external entities and malformed XML`() {
    assertCode(
      "ERR_1015",
      """
      <!DOCTYPE ReadingList [
        <!ENTITY secret SYSTEM "file:///synthetic/secret">
      ]>
      <ReadingList><Name>&secret;</Name><Books><Book Series="S" Number="1"/></Books></ReadingList>
      """.trimIndent(),
    )
    assertCode("ERR_1015", "<ReadingList>")
  }

  @Test
  fun `reports missing name books and book fields with Komga codes`() {
    assertCode(
      "ERR_1030",
      "<ReadingList><Books><Book Series=\"S\" Number=\"1\"/></Books></ReadingList>",
    )
    assertCode("ERR_1029", "<ReadingList><Name>Synthetic</Name><Books/></ReadingList>")
    assertCode(
      "ERR_1031",
      "<ReadingList><Name>Synthetic</Name><Books><Book Number=\"1\"/></Books></ReadingList>",
    )
  }

  private fun assertCode(
    expected: String,
    xml: String,
  ) {
    val error =
      assertFailsWith<ReadListImportException> {
        parser.parse(xml.encodeToByteArray())
      }
    assertEquals(expected, error.code)
  }
}
