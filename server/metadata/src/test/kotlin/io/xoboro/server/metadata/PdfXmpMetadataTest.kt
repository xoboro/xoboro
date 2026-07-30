package io.xoboro.server.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfXmpMetadataTest {
  @Test
  fun `reads Dublin Core fields from a well-formed packet`() {
    val snapshot = PdfXmpMetadata.parse(packet())

    assertEquals("Synthetic Document", snapshot.title)
    assertEquals("Synthetic description", snapshot.description)
    assertEquals("2024-03-17", snapshot.createDate)
    assertEquals(listOf("Jane Doe", "John Roe"), snapshot.creators)
    assertEquals(setOf("synthetic", "fixture"), snapshot.subjects)
  }

  @Test
  fun `keeps creators as separate items without guessing a separator`() {
    // The whole reason to read XMP for authors: a comma inside a name is part of the name here, where
    // the information dictionary's single free-text field would have to be split heuristically.
    val snapshot =
      PdfXmpMetadata.parse(
        packet(creators = listOf("Doe, Jane", "Roe, John")),
      )

    assertEquals(listOf("Doe, Jane", "Roe, John"), snapshot.creators)
  }

  @Test
  fun `prefers the x-default title alternative`() {
    val snapshot =
      PdfXmpMetadata.parse(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
            <rdf:Description>
              <dc:title>
                <rdf:Alt>
                  <rdf:li xml:lang="fr">Titre</rdf:li>
                  <rdf:li xml:lang="x-default">Default Title</rdf:li>
                </rdf:Alt>
              </dc:title>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
      )

    assertEquals("Default Title", snapshot.title)
  }

  @Test
  fun `falls back to the first alternative when none is x-default`() {
    val snapshot =
      PdfXmpMetadata.parse(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
            <rdf:Description>
              <dc:title>
                <rdf:Alt>
                  <rdf:li xml:lang="fr">Titre</rdf:li>
                  <rdf:li xml:lang="de">Titel</rdf:li>
                </rdf:Alt>
              </dc:title>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
      )

    // A title in the wrong language beats no title.
    assertEquals("Titre", snapshot.title)
  }

  @Test
  fun `accepts a bare value where a container was expected`() {
    val snapshot =
      PdfXmpMetadata.parse(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
            <rdf:Description>
              <dc:title>Bare Title</dc:title>
              <dc:creator>Jane Doe, John Roe</dc:creator>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
      )

    assertEquals("Bare Title", snapshot.title)
    // Deliberately one item. Splitting it would reintroduce exactly the guessing that reading XMP for
    // this field exists to avoid, and a single unsplit name is better than two wrong ones.
    assertEquals(listOf("Jane Doe, John Roe"), snapshot.creators)
  }

  @Test
  fun `keeps only the date part of a timestamp`() {
    val snapshot = PdfXmpMetadata.parse(packet(createDate = "2024-03-17T14:32:05+09:00"))

    // A release date with a time and offset would be invented precision for "when was this published".
    assertEquals("2024-03-17", snapshot.createDate)
  }

  @Test
  fun `ignores a timestamp that is not an ISO date`() {
    assertEquals(null, PdfXmpMetadata.parse(packet(createDate = "D:20240317143205")).createDate)
    assertEquals(null, PdfXmpMetadata.parse(packet(createDate = "unknown")).createDate)
  }

  @Test
  fun `treats an unreadable or absent packet as no metadata`() {
    assertTrue(PdfXmpMetadata.parse("").isEmpty)
    assertTrue(PdfXmpMetadata.parse("   ").isEmpty)
    // Truncated mid-element: common in the wild, and must not be an error.
    assertTrue(PdfXmpMetadata.parse("<x:xmpmeta><rdf:RDF><rdf:Desc").isEmpty)
    assertTrue(PdfXmpMetadata.parse("not xml at all").isEmpty)
  }

  @Test
  fun `ignores blank field values`() {
    val snapshot =
      PdfXmpMetadata.parse(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
            <rdf:Description>
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">   </rdf:li></rdf:Alt></dc:title>
              <dc:creator><rdf:Seq><rdf:li> </rdf:li><rdf:li>Jane Doe</rdf:li></rdf:Seq></dc:creator>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
      )

    assertEquals(null, snapshot.title)
    assertEquals(listOf("Jane Doe"), snapshot.creators)
  }

  private fun packet(
    title: String = "Synthetic Document",
    description: String = "Synthetic description",
    createDate: String = "2024-03-17",
    creators: List<String> = listOf("Jane Doe", "John Roe"),
    subjects: List<String> = listOf("synthetic", "fixture"),
  ): String =
    """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:dc="http://purl.org/dc/elements/1.1/"
               xmlns:xmp="http://ns.adobe.com/xap/1.0/">
        <rdf:Description>
          <dc:title><rdf:Alt><rdf:li xml:lang="x-default">$title</rdf:li></rdf:Alt></dc:title>
          <dc:description>
            <rdf:Alt><rdf:li xml:lang="x-default">$description</rdf:li></rdf:Alt>
          </dc:description>
          <xmp:CreateDate>$createDate</xmp:CreateDate>
          <dc:creator>
            <rdf:Seq>${creators.joinToString("") { "<rdf:li>$it</rdf:li>" }}</rdf:Seq>
          </dc:creator>
          <dc:subject>
            <rdf:Bag>${subjects.joinToString("") { "<rdf:li>$it</rdf:li>" }}</rdf:Bag>
          </dc:subject>
        </rdf:Description>
      </rdf:RDF>
    </x:xmpmeta>
    """.trimIndent()
}
