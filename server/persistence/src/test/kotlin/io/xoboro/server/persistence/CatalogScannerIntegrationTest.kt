package io.xoboro.server.persistence

import io.xoboro.core.application.CatalogScanner
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.SourceLocation
import io.xoboro.server.sources.local.LocalSourceInventory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class CatalogScannerIntegrationTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `local inventory reconciliation preserves identity across move change and restart`() {
    val root = tempDirectory.resolve("library").createDirectory()
    val originalDirectory = root.resolve("Original").createDirectory()
    val originalFile = originalDirectory.resolve("book.cbz")
    originalFile.writeText("synthetic-v1")
    val databasePath = tempDirectory.resolve("catalog.sqlite")
    val library =
      Library(
        id = LibraryId("library-1"),
        name = "Synthetic library",
        root = SourceLocation("local", root.toUri().toString()),
        createdAtMillis = 1L,
      )
    lateinit var originalBookId: String

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { database ->
      JooqLibraryRepository(database).insert(library)
      val clock = AtomicLong(10L)
      val scanner =
        CatalogScanner(
          inventories = listOf(LocalSourceInventory()),
          reconciliationStore = JooqCatalogReconciliationStore(database),
          currentTimeMillis = clock::getAndIncrement,
          batchSize = 1,
        )

      val initial = scanner.scan(library, deep = false)
      assertEquals(1L, initial.addedBooks)
      val bookRepository = JooqBookRepository(database)
      val rawModified =
        database.dsl
          .fetchOne("SELECT CAST(file_modified_ms AS TEXT) FROM book")
          ?.get(0, String::class.java)
          ?.toLong()
          ?: error("missing timestamp")
      assertEquals(true, rawModified >= 0, "raw modified timestamp: $rawModified")
      originalBookId = bookRepository.findAllByLibraryId(library.id).single().id.value

      val movedDirectory = root.resolve("Moved").createDirectory()
      val movedFile = Files.move(originalFile, movedDirectory.resolve("book.cbz"))
      val moved = scanner.scan(library, deep = false)
      assertEquals(1L, moved.movedBooks)
      assertEquals(
        originalBookId,
        bookRepository.findByLibraryIdAndRelativePath(library.id, "Moved/book.cbz")?.id?.value,
      )

      movedFile.writeText("synthetic-v2-with-different-size")
      val changed = scanner.scan(library, deep = false)
      assertEquals(1L, changed.changedBooks)
      assertEquals(
        originalBookId,
        bookRepository.findByLibraryIdAndRelativePath(library.id, "Moved/book.cbz")?.id?.value,
      )
    }

    XoboroDatabase.open(DatabaseConfig(databasePath)).use { reopened ->
      val persistedLibrary = assertNotNull(JooqLibraryRepository(reopened).findByIdOrNull(library.id))
      val clock = AtomicLong(100L)
      val scanner =
        CatalogScanner(
          inventories = listOf(LocalSourceInventory()),
          reconciliationStore = JooqCatalogReconciliationStore(reopened),
          currentTimeMillis = clock::getAndIncrement,
        )

      val repeated = scanner.scan(persistedLibrary, deep = false)

      assertEquals(0L, repeated.addedBooks)
      assertEquals(0L, repeated.changedBooks)
      assertEquals(0L, repeated.movedBooks)
      assertEquals(
        originalBookId,
        JooqBookRepository(reopened)
          .findByLibraryIdAndRelativePath(library.id, "Moved/book.cbz")
          ?.id
          ?.value,
      )
    }
  }
}
