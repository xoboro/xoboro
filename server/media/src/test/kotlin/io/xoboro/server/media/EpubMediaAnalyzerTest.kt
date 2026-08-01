package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EpubMediaAnalyzerTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `analyzes fixed layout resources navigation positions and page hashes`() {
    val path = tempDirectory.resolve("synthetic-fixed.epub")
    writeEpub(path, fixedLayout = true)

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-fixed"),
        path = path,
        analyzeDimensions = true,
        hashPages = true,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertEquals(MediaProfile.EPUB, media.profile)
    assertTrue(media.epubDivinaCompatible)
    assertTrue(media.epubIsFixedLayout)
    assertEquals(2, media.pageCount)
    assertEquals(listOf("OEBPS/images/page1.png", "OEBPS/images/page2.png"), media.pages.map { it.fileName })
    assertEquals(32, media.pages.first().dimension?.width)
    assertTrue(media.pages.all { it.fileHash.isNotBlank() })
    assertEquals(2, media.files.count { it.kind == MediaFileKind.EPUB_PAGE })
    assertEquals("Synthetic contents", media.toc.single().title)
    assertEquals("Synthetic section", media.toc.single().children.single().title)
    assertEquals("Start", media.landmarks.single().title)
    assertEquals("Page one", media.pageList.single().title)
    assertEquals(listOf(1, 2), media.positions.map { it.position })
    // `(position - 1) / count`, the Readium convention: a position reports where it starts.
    // Both ends are pinned deliberately. Asserting only the last value cannot tell this
    // apart from the `position / count` convention it replaced, because a two-position book
    // reports 0.5 as its last value under the new rule and as its *first* under the old one.
    assertEquals(listOf(0F, 0.5F), media.positions.map { it.totalProgression })
  }

  @Test
  fun `analyzes reflowable text without exposing comic pages`() {
    val path = tempDirectory.resolve("synthetic-reflowable.epub")
    writeEpub(path, fixedLayout = false)

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-reflowable"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 2,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertTrue(!media.epubDivinaCompatible)
    assertTrue(!media.epubIsFixedLayout)
    assertTrue(media.pages.isEmpty())
    assertTrue(media.pageCount > 0)
    assertTrue(media.positions.isNotEmpty())
    // A reflowable spine item is chunked, so this fixture has more positions than the
    // fixed-layout one above and does not know how many. Both ends of the Readium
    // convention are still fixed regardless of n: the publication starts at 0, and 1 is the
    // end of the publication rather than a position, so nothing reports it. The previous
    // `position / count` convention violated both.
    assertEquals(0F, media.positions.first().totalProgression)
    assertTrue(media.positions.none { it.totalProgression >= 1F })
    // Page count is derived from compressed entry sizes and positions from uncompressed
    // ones, so `pageCount <= positions.size`. `KoreaderSyncRoutes.pageFor` relies on that
    // to guarantee the last position reaches the last page.
    assertTrue(media.pageCount <= media.positions.size)
  }

  @Test
  fun `marks invalid epub as analysis error`() {
    val path = tempDirectory.resolve("invalid.epub")
    Files.writeString(path, "not an epub")

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-invalid"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 3,
      )

    assertEquals(MediaStatus.ERROR, media.status)
    assertEquals(MediaAnalysisComment.UNREADABLE_CONTAINER, media.comment)
  }

  @Test
  fun `reports a publication whose text is encrypted as unsupported`() {
    val path = tempDirectory.resolve("drm.epub")
    writeEpub(path, fixedLayout = false, encryptedResource = "OEBPS/chapter1.xhtml" to AES_ALGORITHM)

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-drm"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    // Without this the publication indexed as READY: the container and package are plaintext, so
    // analysis succeeded and produced a catalog entry whose text no reader can render.
    assertEquals(MediaStatus.UNSUPPORTED, media.status)
    assertEquals(MediaAnalysisComment.ENCRYPTED, media.comment)
  }

  /**
   * `META-INF/encryption.xml` is not a DRM marker. Isolates the algorithm half of the rule: the same
   * spine resource, declared under the obfuscation algorithm, stays readable.
   */
  @Test
  fun `keeps a publication with obfuscated resources readable`() {
    val path = tempDirectory.resolve("obfuscated.epub")
    writeEpub(
      path,
      fixedLayout = false,
      encryptedResource = "OEBPS/chapter1.xhtml" to OBFUSCATION_ALGORITHM,
    )

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-obfuscated"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
  }

  /** Isolates the other half: real encryption, but on a resource no reader has to render. */
  @Test
  fun `keeps a publication with an encrypted non-spine resource readable`() {
    val path = tempDirectory.resolve("encrypted-font.epub")
    writeEpub(
      path,
      fixedLayout = false,
      encryptedResource = "OEBPS/fonts/synthetic.otf" to AES_ALGORITHM,
    )

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-encrypted-font"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
  }

  @Test
  fun `marks the manifest item an EPUB3 cover-image property declares`() {
    val path = tempDirectory.resolve("cover-epub3.epub")
    writeEpubWithCover(path, declaration = CoverDeclaration.PROPERTY)

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-cover-epub3"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(MediaStatus.READY, media.status)
    assertEquals(
      listOf("OEBPS/images/cover.png"),
      media.files.filter { it.kind == MediaFileKind.EPUB_COVER }.map { it.fileName },
    )
  }

  @Test
  fun `finds the cover through the legacy EPUB2 meta name=cover fallback`() {
    val path = tempDirectory.resolve("cover-epub2.epub")
    writeEpubWithCover(path, declaration = CoverDeclaration.META)

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-cover-epub2"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertEquals(
      listOf("OEBPS/images/cover.png"),
      media.files.filter { it.kind == MediaFileKind.EPUB_COVER }.map { it.fileName },
    )
  }

  @Test
  fun `marks no cover when the OPF declares none`() {
    val path = tempDirectory.resolve("no-cover.epub")
    writeEpubWithCover(path, declaration = null)

    val media =
      EpubMediaAnalyzer().analyze(
        bookId = BookId("book-no-cover"),
        path = path,
        analyzeDimensions = false,
        createdAtMillis = 1,
      )

    assertTrue(media.files.none { it.kind == MediaFileKind.EPUB_COVER })
  }

  private enum class CoverDeclaration { PROPERTY, META }

  private fun writeEpubWithCover(
    path: Path,
    declaration: CoverDeclaration?,
  ) {
    ZipOutputStream(Files.newOutputStream(path)).use { archive ->
      archive.entry("mimetype", EpubMediaAnalyzer.EPUB_MEDIA_TYPE.encodeToByteArray())
      archive.entry(
        "META-INF/container.xml",
        """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent().encodeToByteArray(),
      )
      val coverProperties = if (declaration == CoverDeclaration.PROPERTY) """ properties="cover-image"""" else ""
      val coverMeta =
        if (declaration == CoverDeclaration.META) {
          """<meta name="cover" content="cover-image"/>"""
        } else {
          ""
        }
      archive.entry(
        "OEBPS/package.opf",
        """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Synthetic publication</dc:title>
            $coverMeta
          </metadata>
          <manifest>
            <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover-image" href="images/cover.png" media-type="image/png"$coverProperties/>
          </manifest>
          <spine>
            <itemref idref="chapter1"/>
          </spine>
        </package>
        """.trimIndent().encodeToByteArray(),
      )
      archive.entry(
        "OEBPS/chapter1.xhtml",
        "<html><body>Synthetic reflowable publication text exceeds the comic image threshold easily.</body></html>"
          .encodeToByteArray(),
      )
      archive.entry("OEBPS/images/cover.png", imageBytes(Color.RED))
    }
  }

  private fun writeEpub(
    path: Path,
    fixedLayout: Boolean,
    encryptedResource: Pair<String, String>? = null,
  ) {
    ZipOutputStream(Files.newOutputStream(path)).use { archive ->
      archive.entry("mimetype", EpubMediaAnalyzer.EPUB_MEDIA_TYPE.encodeToByteArray())
      encryptedResource?.let { (resource, algorithm) ->
        archive.entry(
          "META-INF/encryption.xml",
          """
          <?xml version="1.0"?>
          <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                      xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData>
              <enc:EncryptionMethod Algorithm="$algorithm"/>
              <enc:CipherData><enc:CipherReference URI="$resource"/></enc:CipherData>
            </enc:EncryptedData>
          </encryption>
          """.trimIndent().encodeToByteArray(),
        )
      }
      archive.entry(
        "META-INF/container.xml",
        """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent().encodeToByteArray(),
      )
      archive.entry(
        "OEBPS/package.opf",
        """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Synthetic publication</dc:title>
            ${if (fixedLayout) "<meta property=\"rendition:layout\">pre-paginated</meta>" else ""}
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
            <item id="chapter2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
            <item id="page1" href="images/page1.png" media-type="image/png"/>
            <item id="page2" href="images/page2.png" media-type="image/png"/>
          </manifest>
          <spine>
            <itemref idref="chapter1"/>
            <itemref idref="chapter2"/>
          </spine>
          <guide>
            <reference type="text" title="Start" href="chapter1.xhtml"/>
          </guide>
        </package>
        """.trimIndent().encodeToByteArray(),
      )
      archive.entry(
        "OEBPS/nav.xhtml",
        """
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <nav epub:type="toc"><ol><li><a href="chapter1.xhtml">Synthetic contents</a><ol><li><a href="chapter2.xhtml">Synthetic section</a></li></ol></li></ol></nav>
            <nav epub:type="landmarks"><ol><li><a href="chapter1.xhtml">Start</a></li></ol></nav>
            <nav epub:type="page-list"><ol><li><a href="chapter1.xhtml">Page one</a></li></ol></nav>
          </body>
        </html>
        """.trimIndent().encodeToByteArray(),
      )
      val text =
        if (fixedLayout) {
          ""
        } else {
          "Synthetic reflowable publication text exceeds the comic image threshold."
        }
      archive.entry(
        "OEBPS/chapter1.xhtml",
        "<html><body>$text<img src=\"images/page1.png\"/></body></html>".encodeToByteArray(),
      )
      archive.entry(
        "OEBPS/chapter2.xhtml",
        "<html><body>$text<img src=\"images/page2.png\"/></body></html>".encodeToByteArray(),
      )
      archive.entry("OEBPS/images/page1.png", imageBytes(Color.BLUE))
      archive.entry("OEBPS/images/page2.png", imageBytes(Color.GREEN))
    }
    assertNotNull(path)
  }

  private fun imageBytes(color: Color): ByteArray {
    val image = BufferedImage(32, 48, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().use { graphics ->
      graphics.color = color
      graphics.fillRect(0, 0, image.width, image.height)
    }
    return ByteArrayOutputStream().use { output ->
      check(ImageIO.write(image, "png", output))
      output.toByteArray()
    }
  }

  private fun ZipOutputStream.entry(
    name: String,
    bytes: ByteArray,
  ) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
  }

  private companion object {
    const val AES_ALGORITHM = "http://www.w3.org/2001/04/xmlenc#aes128-cbc"
    const val OBFUSCATION_ALGORITHM = "http://www.idpf.org/2008/embedding"
  }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
  try {
    block(this)
  } finally {
    dispose()
  }
