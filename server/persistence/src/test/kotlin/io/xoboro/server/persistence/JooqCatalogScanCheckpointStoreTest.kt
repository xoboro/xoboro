package io.xoboro.server.persistence

import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.SourceLocation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqCatalogScanCheckpointStoreTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `matches only the exact source settings and fingerprint`() {
    withStore("matches") { store, libraries, _ ->
      val library = libraryFixture(exclusions = linkedSetOf("cache", "draft"))
      assertFalse(store.exists(library))
      libraries.insert(library)
      store.replace(library, sourceFingerprint = "fingerprint-1", completedAtMillis = 10L)

      assertTrue(store.exists(library))
      assertTrue(store.matches(library, "fingerprint-1"))
      assertFalse(store.matches(library, "fingerprint-2"))
      assertFalse(
        store.matches(
          library.copy(root = SourceLocation("local", "file:///other")),
          "fingerprint-1",
        ),
      )
      assertFalse(
        store.matches(
          library.copy(settings = library.settings.copy(scanPdf = false)),
          "fingerprint-1",
        ),
      )
      assertTrue(
        store.matches(
          library.copy(
            settings = library.settings.copy(
              scanDirectoryExclusions = linkedSetOf("draft", "cache"),
            ),
          ),
          "fingerprint-1",
        ),
      )
    }
  }

  @Test
  fun `replacement is idempotent and library deletion removes its checkpoint`() {
    withStore("replace") { store, libraries, database ->
      val library = libraryFixture()
      libraries.insert(library)

      store.replace(library, sourceFingerprint = "old", completedAtMillis = 10L)
      store.replace(library, sourceFingerprint = "new", completedAtMillis = 20L)

      assertFalse(store.matches(library, "old"))
      assertTrue(store.matches(library, "new"))
      assertEquals(
        20L,
        database.dsl
          .fetchOne(
            "SELECT completed_at_ms FROM catalog_scan_checkpoint WHERE library_id = ?",
            library.id.value,
          )?.get(0, Long::class.java),
      )

      libraries.delete(library.id)
      assertEquals(
        0,
        database.dsl.fetchValue("SELECT count(*) FROM catalog_scan_checkpoint", Int::class.java),
      )
    }
  }

  private fun withStore(
    name: String,
    block: (JooqCatalogScanCheckpointStore, JooqLibraryRepository, XoboroDatabase) -> Unit,
  ) {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("$name.sqlite"))).use { database ->
      block(
        JooqCatalogScanCheckpointStore(database),
        JooqLibraryRepository(database),
        database,
      )
    }
  }

  private fun libraryFixture(exclusions: Set<String> = emptySet()): Library =
    Library(
      id = LibraryId("library-1"),
      name = "Synthetic library",
      root = SourceLocation("local", "file:///library"),
      settings =
        LibrarySettings(
          scanCbx = true,
          scanPdf = true,
          scanEpub = false,
          scanForceModifiedTime = true,
          scanDirectoryExclusions = exclusions,
          oneshotsDirectory = "oneshots",
        ),
      createdAtMillis = 1L,
    )
}
