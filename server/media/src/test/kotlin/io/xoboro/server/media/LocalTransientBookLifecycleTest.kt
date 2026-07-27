package io.xoboro.server.media

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.SourceLocation
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalTransientBookLifecycleTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `scans analyzes and streams supported files outside libraries`() {
    val imports = Files.createDirectory(tempDirectory.resolve("imports"))
    createArchive(imports.resolve("synthetic.cbz"))
    Files.writeString(imports.resolve("ignored.txt"), "ignored")
    val lifecycle =
      LocalTransientBookLifecycle(
        libraries = TestLibraryRepository(),
        idFactory = { "transient-1" },
        currentTimeMillis = { 20 },
      )

    val scanned = lifecycle.scan(imports.toString())
    assertEquals(listOf("synthetic.cbz"), scanned.map { it.name })
    assertEquals(scanned.single(), lifecycle.findByIdOrNull("transient-1"))
    assertEquals(MediaStatus.READY, lifecycle.analyze("transient-1")?.media?.status)

    val content = requireNotNull(lifecycle.openPage("transient-1", 1))
    val bytes =
      try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
          val read = content.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
        }
        output.toByteArray()
      } finally {
        content.close()
      }
    assertTrue(bytes.isNotEmpty())
    assertEquals("image/png", content.mediaType)
  }

  @Test
  fun `rejects paths that overlap a configured local library`() {
    val libraryRoot = Files.createDirectory(tempDirectory.resolve("library"))
    val lifecycle =
      LocalTransientBookLifecycle(
        libraries =
          TestLibraryRepository(
            Library(
              id = LibraryId("library-1"),
              name = "Synthetic",
              root = SourceLocation("local", libraryRoot.toUri().toString()),
              createdAtMillis = 1,
            ),
          ),
        idFactory = { "transient-1" },
        currentTimeMillis = { 20 },
      )

    val failure =
      assertFailsWith<IllegalArgumentException> {
        lifecycle.scan(libraryRoot.toString())
      }
    assertEquals(LocalTransientBookLifecycle.ERROR_LIBRARY_PATH, failure.message)
  }

  private fun createArchive(path: Path) {
    val png =
      ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
      }
    ZipOutputStream(Files.newOutputStream(path)).use { output ->
      output.putNextEntry(ZipEntry("001.png"))
      output.write(png)
      output.closeEntry()
    }
  }

  private class TestLibraryRepository(
    private vararg val libraries: Library,
  ) : LibraryRepository {
    override fun findById(id: LibraryId): Library =
      requireNotNull(findByIdOrNull(id))

    override fun findByIdOrNull(id: LibraryId): Library? =
      libraries.firstOrNull { it.id == id }

    override fun findAll(): List<Library> = libraries.toList()

    override fun findAllByIds(ids: Collection<LibraryId>): List<Library> =
      libraries.filter { it.id in ids }

    override fun insert(library: Library) = Unit

    override fun update(library: Library) = Unit

    override fun delete(id: LibraryId) = Unit

    override fun deleteAll() = Unit

    override fun count(): Long = libraries.size.toLong()
  }
}
