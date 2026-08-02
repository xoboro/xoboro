package io.xoboro.server.sources.local

import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.SourceImportRequest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalSourceMutationAccessTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `copies moves and hard links imports inside the library root`() {
    val root = Files.createDirectories(tempDirectory.resolve("library"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val sourceCopy = Files.writeString(tempDirectory.resolve("copy.cbz"), "copy")
    val sourceMove = Files.writeString(tempDirectory.resolve("move.cbz"), "move")
    val sourceLink = Files.writeString(tempDirectory.resolve("link.cbz"), "link")
    val access = LocalSourceMutationAccess()

    access.import(
      root.toUri().toString(),
      series.toUri().toString(),
      SourceImportRequest(sourceCopy.toString(), "copied.cbz", SourceCopyMode.COPY),
    )
    access.import(
      root.toUri().toString(),
      series.toUri().toString(),
      SourceImportRequest(sourceMove.toString(), "moved.cbz", SourceCopyMode.MOVE),
    )
    access.import(
      root.toUri().toString(),
      series.toUri().toString(),
      SourceImportRequest(sourceLink.toString(), "linked.cbz", SourceCopyMode.HARDLINK),
    )

    assertEquals("copy", Files.readString(series.resolve("copied.cbz")))
    assertEquals("move", Files.readString(series.resolve("moved.cbz")))
    assertTrue(!Files.exists(sourceMove))
    assertTrue(Files.isSameFile(sourceLink, series.resolve("linked.cbz")))
  }

  @Test
  fun `atomically replaces an upgrade and deletes contained media`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-upgrade"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val destination = Files.writeString(series.resolve("chapter.cbz"), "old")
    val source = Files.writeString(tempDirectory.resolve("upgrade.cbz"), "new")
    val access = LocalSourceMutationAccess()

    val imported =
      access.import(
        root.toUri().toString(),
        series.toUri().toString(),
        SourceImportRequest(
          sourceFile = source.toString(),
          destinationName = "chapter.cbz",
          copyMode = SourceCopyMode.COPY,
          replaceExisting = true,
        ),
      )

    assertEquals(destination.toRealPath(), Path.of(URI(imported)).toRealPath())
    assertEquals("new", Files.readString(destination))
    assertTrue(access.delete(root.toUri().toString(), destination.toUri().toString()))
    assertTrue(!Files.exists(destination))
  }

  @Test
  fun `rejects destination traversal and deletion outside the root`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-safe"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val source = Files.writeString(tempDirectory.resolve("outside.cbz"), "outside")
    val access = LocalSourceMutationAccess()

    assertFailsWith<IllegalArgumentException> {
      access.import(
        root.toUri().toString(),
        series.toUri().toString(),
        SourceImportRequest(source.toString(), "../escape.cbz", SourceCopyMode.COPY),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      access.delete(root.toUri().toString(), source.toUri().toString())
    }
    assertTrue(Files.exists(source))
  }

  @Test
  fun `atomically removes selected archive entries`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-archive"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = series.resolve("chapter.cbz")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
      mapOf(
        "001.jpg" to "first",
        "002.jpg" to "second",
        "ComicInfo.xml" to "<ComicInfo/>",
      ).forEach { (name, value) ->
        output.putNextEntry(ZipEntry(name))
        output.write(value.encodeToByteArray())
        output.closeEntry()
      }
    }

    val removed =
      LocalSourceMutationAccess().removeArchiveEntries(
        root.toUri().toString(),
        archive.toUri().toString(),
        setOf("002.jpg"),
      )

    assertEquals(1, removed)
    assertEquals(
      listOf("001.jpg", "ComicInfo.xml"),
      ZipInputStream(Files.newInputStream(archive)).use { input ->
        buildList {
          while (true) add(input.nextEntry?.name ?: break)
        }
      },
    )
  }

  @Test
  fun `repairs an extension and returns canonical source facts`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-repair"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val source = Files.writeString(series.resolve("chapter.cbr"), "content")

    val result =
      LocalSourceMutationAccess().renameExtension(
        root.toUri().toString(),
        source.toUri().toString(),
        "cbz",
      )

    val repaired = series.resolve("chapter.cbz")
    assertTrue(Files.exists(repaired))
    assertTrue(!Files.exists(source))
    assertEquals("Synthetic series/chapter.cbz", result.relativePath)
    assertEquals("chapter.cbz", result.name)
    assertEquals(Files.size(repaired), result.size)
    assertTrue(Files.isSameFile(repaired, Path.of(URI(result.itemId))))
  }

  @Test
  fun `atomically replaces media while changing its extension`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-convert"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val source = Files.writeString(series.resolve("chapter.cbr"), "old")
    val replacement = Files.writeString(tempDirectory.resolve("replacement.cbz"), "new")

    val result =
      LocalSourceMutationAccess().replaceWithFile(
        root.toUri().toString(),
        source.toUri().toString(),
        replacement.toString(),
        "cbz",
      )

    val converted = series.resolve("chapter.cbz")
    assertEquals("new", Files.readString(converted))
    assertTrue(!Files.exists(source))
    assertEquals("Synthetic series/chapter.cbz", result.relativePath)
    assertTrue(Files.isSameFile(converted, Path.of(URI(result.itemId))))
  }

  @Test
  fun `retained entries survive byte identical after removing entries`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-verified-removal"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = series.resolve("chapter.cbz")
    writeZip(
      archive,
      mapOf(
        "001.jpg" to "first",
        "002.jpg" to "second",
        "ComicInfo.xml" to "<ComicInfo/>",
      ),
    )

    val removed =
      LocalSourceMutationAccess().removeArchiveEntries(
        root.toUri().toString(),
        archive.toUri().toString(),
        setOf("002.jpg"),
      )

    assertEquals(1, removed)
    ZipFile(archive.toFile()).use { zip ->
      assertEquals("first", zip.getInputStream(zip.getEntry("001.jpg")).readBytes().decodeToString())
      assertEquals(
        "<ComicInfo/>",
        zip.getInputStream(zip.getEntry("ComicInfo.xml")).readBytes().decodeToString(),
      )
      assertNull(zip.getEntry("002.jpg"))
    }
  }

  @Test
  fun `refuses to replace an archive when the rewrite would silently drop a retained entry`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-truncated"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = series.resolve("chapter.cbz")
    val bytes =
      storedZipBytes(
        listOf(
          "001.jpg" to "first",
          "002.jpg" to "second",
          "003.xml" to "<ComicInfo/>",
        ),
      )
    zeroLocalHeaderSizeAndCrc(bytes, "002.jpg")
    Files.write(archive, bytes)
    val originalBytes = Files.readAllBytes(archive)

    val failure =
      assertFailsWith<IllegalStateException> {
        LocalSourceMutationAccess().removeArchiveEntries(
          root.toUri().toString(),
          archive.toUri().toString(),
          setOf("002.jpg"),
        )
      }

    assertTrue(failure.message.orEmpty().contains(archive.toString()))
    assertTrue(originalBytes.contentEquals(Files.readAllBytes(archive)))
  }

  @Test
  fun `quarantines the original archive instead of overwriting it`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-quarantine"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = series.resolve("chapter.cbz")
    writeZip(archive, mapOf("001.jpg" to "first", "002.jpg" to "second"))
    val quarantine = Files.createDirectories(tempDirectory.resolve("quarantine"))
    val access = LocalSourceMutationAccess(quarantine = quarantine)

    val removed =
      access.removeArchiveEntries(
        root.toUri().toString(),
        archive.toUri().toString(),
        setOf("002.jpg"),
      )

    assertEquals(1, removed)
    assertEquals(listOf("001.jpg"), zipEntryNames(archive))
    val quarantinedFiles =
      Files.walk(quarantine).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "chapter.cbz" }.toList()
      }
    assertEquals(1, quarantinedFiles.size)
    assertEquals(listOf("001.jpg", "002.jpg"), zipEntryNames(quarantinedFiles.single()))
  }

  @Test
  fun `rejects a quarantine directory inside the library root`() {
    val root = Files.createDirectories(tempDirectory.resolve("library-guard"))
    val series = Files.createDirectories(root.resolve("Synthetic series"))
    val archive = series.resolve("chapter.cbz")
    writeZip(archive, mapOf("001.jpg" to "first", "002.jpg" to "second"))
    val quarantine = Files.createDirectories(root.resolve(".xoboro-quarantine"))
    val access = LocalSourceMutationAccess(quarantine = quarantine)

    assertFailsWith<IllegalArgumentException> {
      access.removeArchiveEntries(
        root.toUri().toString(),
        archive.toUri().toString(),
        setOf("002.jpg"),
      )
    }
    assertEquals(listOf("001.jpg", "002.jpg"), zipEntryNames(archive))
  }

  private fun writeZip(
    path: Path,
    entries: Map<String, String>,
  ) {
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      entries.forEach { (name, value) ->
        output.putNextEntry(ZipEntry(name))
        output.write(value.encodeToByteArray())
        output.closeEntry()
      }
    }
  }

  private fun zipEntryNames(path: Path): List<String> =
    ZipInputStream(Files.newInputStream(path)).use { input ->
      buildList { while (true) add(input.nextEntry?.name ?: break) }
    }

  /**
   * Builds a zip with STORED entries whose sizes and CRCs are set explicitly, so the local file
   * header carries authoritative size/CRC fields instead of relying on a trailing data
   * descriptor. That makes [zeroLocalHeaderSizeAndCrc] able to fool a sequential reader
   * deterministically.
   */
  private fun storedZipBytes(entries: List<Pair<String, String>>): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { output ->
      entries.forEach { (name, value) ->
        val content = value.encodeToByteArray()
        val checksum = CRC32().apply { update(content) }
        val entry =
          ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = content.size.toLong()
            crc = checksum.value
          }
        output.putNextEntry(entry)
        output.write(content)
        output.closeEntry()
      }
    }
    return buffer.toByteArray()
  }

  /**
   * Locates the local file header for [entryName] by scanning for its signature, then zeroes the
   * CRC (offset 14) and compressed/uncompressed size fields (offsets 18 and 22) so a sequential
   * reader believes the entry holds zero bytes. The real bytes remain physically present right
   * after the header, so a reader that trusts the (now falsified) declared size lands mid-way
   * through the following entry's local header signature the next time it looks for one — which
   * `ZipInputStream` treats as end-of-archive rather than an error, silently losing every entry
   * that follows.
   */
  private fun zeroLocalHeaderSizeAndCrc(
    bytes: ByteArray,
    entryName: String,
  ) {
    val nameBytes = entryName.encodeToByteArray()
    var offset = 0
    while (offset <= bytes.size - 30) {
      val isLocalHeaderSignature =
        bytes[offset] == 0x50.toByte() &&
          bytes[offset + 1] == 0x4b.toByte() &&
          bytes[offset + 2] == 0x03.toByte() &&
          bytes[offset + 3] == 0x04.toByte()
      if (isLocalHeaderSignature) {
        val nameLength =
          (bytes[offset + 26].toInt() and 0xff) or ((bytes[offset + 27].toInt() and 0xff) shl 8)
        val candidateName = bytes.copyOfRange(offset + 30, offset + 30 + nameLength)
        if (candidateName.contentEquals(nameBytes)) {
          for (fieldOffset in 14..25) bytes[offset + fieldOffset] = 0
          return
        }
      }
      offset += 1
    }
    error("Local file header for '$entryName' not found in synthetic archive")
  }
}
