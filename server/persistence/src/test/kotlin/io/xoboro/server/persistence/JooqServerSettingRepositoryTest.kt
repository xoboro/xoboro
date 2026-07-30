package io.xoboro.server.persistence

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqServerSettingRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `creates updates and preserves server secrets across restart`() {
    val path = tempDirectory.resolve("server-settings.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqServerSettingRepository(database)
      assertEquals("synthetic-secret-1", repository.findOrCreate("REMEMBER_ME_KEY") { "synthetic-secret-1" })
      assertEquals("synthetic-secret-1", repository.findOrCreate("REMEMBER_ME_KEY") { "unused-secret" })
      repository.put("REMEMBER_ME_KEY", "synthetic-secret-2")
      assertEquals("synthetic-secret-2", repository.find("REMEMBER_ME_KEY"))
      repository.put("EPHEMERAL_SETTING", "synthetic-value")
      repository.delete("EPHEMERAL_SETTING")
      assertNull(repository.find("EPHEMERAL_SETTING"))
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqServerSettingRepository(database)
      assertEquals("synthetic-secret-2", repository.findOrCreate("REMEMBER_ME_KEY") { "unused-secret" })
    }
  }

  /**
   * `findOrCreate` used to look the key up and then insert inside one transaction, so a concurrent
   * commit against the read snapshot it had already taken made the later insert fail to acquire the
   * write lock - `SQLITE_BUSY_SNAPSHOT`, which `busy_timeout` does not wait out because the failure
   * is a mid-transaction lock upgrade rather than a fresh transaction's first write.
   *
   * Every creation here is for a distinct key, so none of them contend with each other: the only
   * competitor is the background writer committing unrelated rows to the same table.
   */
  @Test
  fun `creates settings while another connection commits to the same table`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("concurrent.sqlite"))).use { database ->
      val repository = JooqServerSettingRepository(database)
      val keepWriting = AtomicBoolean(true)
      val failures = mutableListOf<Throwable>()
      val executor = Executors.newFixedThreadPool(CREATOR_THREADS + 1)
      try {
        val writer =
          executor.submit {
            var counter = 0L
            while (keepWriting.get()) {
              repository.put("SYNTHETIC_COMPETING_WRITER", "value-${counter++}")
            }
          }
        val creators =
          (0 until CREATOR_THREADS).map { thread ->
            executor.submit {
              repeat(CREATIONS_PER_THREAD) { iteration ->
                val key = "SYNTHETIC_KEY_${thread}_$iteration"
                val created = repository.findOrCreate(key) { "value-$thread-$iteration" }
                assertEquals("value-$thread-$iteration", created)
              }
            }
          }
        creators.forEach { creator ->
          runCatching { creator.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { failure -> failures += failure }
        }
        keepWriting.set(false)
        writer.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      } finally {
        keepWriting.set(false)
        executor.shutdownNow()
      }
      assertTrue(failures.isEmpty(), "Concurrent findOrCreate failed: ${failures.map { it.message }}")
      assertEquals(
        CREATOR_THREADS * CREATIONS_PER_THREAD,
        (0 until CREATOR_THREADS).sumOf { thread ->
          (0 until CREATIONS_PER_THREAD).count { iteration ->
            repository.find("SYNTHETIC_KEY_${thread}_$iteration") != null
          }
        },
      )
    }
  }

  private companion object {
    const val CREATOR_THREADS = 4
    const val CREATIONS_PER_THREAD = 250
    const val CONCURRENCY_TIMEOUT_SECONDS = 120L
  }
}
