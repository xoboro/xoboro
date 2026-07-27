package io.xoboro.server.persistence

import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqAnnouncementReadRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `persists deduplicates and cascades announcement reads`() {
    val path = tempDirectory.resolve("announcement-reads.sqlite")
    val user = syntheticUser()
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val users = JooqUserRepository(database)
      users.insert(user)
      val reads = JooqAnnouncementReadRepository(database)

      reads.markRead(user.id, setOf("announcement-2", "announcement-1"))
      reads.markRead(user.id, setOf("announcement-1"))

      assertEquals(
        setOf("announcement-1", "announcement-2"),
        reads.findReadIds(user.id),
      )
    }
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val reads = JooqAnnouncementReadRepository(database)
      assertEquals(2, reads.findReadIds(user.id).size)
      JooqUserRepository(database).delete(user.id)
      assertTrue(reads.findReadIds(user.id).isEmpty())
    }
  }

  private fun syntheticUser(): User =
    User(
      id = UserId("user-1"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-hash",
      createdAtMillis = 1,
    )
}
