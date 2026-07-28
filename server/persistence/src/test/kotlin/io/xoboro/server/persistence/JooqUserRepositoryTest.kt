package io.xoboro.server.persistence

import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqUserRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `round trips complete users across restart`() {
    val path = tempDirectory.resolve("restart.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      insertLibrary(database)
      JooqUserRepository(database).insert(userFixture())
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqUserRepository(database)
      val restored = requireNotNull(repository.findByEmailIgnoreCaseOrNull("READER@example.invalid"))

      assertEquals(USER_ID, restored.id)
      assertEquals("reader@example.invalid", restored.email)
      assertEquals("\$2a\$10\$synthetic", restored.passwordHash)
      assertEquals(setOf(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING), restored.roles)
      assertEquals(setOf(LIBRARY_ID), restored.sharedLibraryIds)
      assertFalse(restored.sharesAllLibraries)
      assertEquals(
        AgeRestriction(16, RestrictionMode.EXCLUDE),
        restored.restrictions.ageRestriction,
      )
      assertEquals(setOf("family"), restored.restrictions.labelsAllow)
      assertEquals(setOf("restricted"), restored.restrictions.labelsExclude)
      assertEquals(1, repository.count())
    }
  }

  @Test
  fun `enforces case-insensitive email uniqueness`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("email.sqlite"))).use { database ->
      val repository = JooqUserRepository(database)
      repository.insert(userFixture(sharedLibraryIds = emptySet()))

      assertFailsWith<UserEmailAlreadyExistsException> {
        repository.insert(
          userFixture(
            id = UserId("user-2"),
            email = "READER@example.invalid",
            sharedLibraryIds = emptySet(),
          ),
        )
      }

      assertEquals(1, repository.count())
    }
  }

  @Test
  fun `updates all user relations atomically`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("update.sqlite"))).use { database ->
      insertLibrary(database)
      val repository = JooqUserRepository(database)
      repository.insert(userFixture())

      repository.update(
        userFixture().copy(
          email = "updated@example.invalid",
          passwordHash = "updated-hash",
          roles = setOf(UserRole.ADMIN),
          sharedLibraryIds = emptySet(),
          sharesAllLibraries = true,
          restrictions = ContentRestrictions(),
          updatedAtMillis = 2,
        ),
      )

      val updated = requireNotNull(repository.findByIdOrNull(USER_ID))
      assertEquals("updated@example.invalid", updated.email)
      assertEquals("updated-hash", updated.passwordHash)
      assertEquals(setOf(UserRole.ADMIN), updated.roles)
      assertTrue(updated.sharedLibraryIds.isEmpty())
      assertTrue(updated.sharesAllLibraries)
      assertFalse(updated.restrictions.isRestricted)
      assertEquals(2, updated.updatedAtMillis)
    }
  }

  @Test
  fun `replaces a password hash only when the expected hash still matches`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("password-hash.sqlite"))).use { database ->
      insertLibrary(database)
      val repository = JooqUserRepository(database)
      repository.insert(userFixture())

      assertFalse(
        repository.replacePasswordHash(
          id = USER_ID,
          expectedHash = "stale-hash",
          replacementHash = "modern-hash",
          updatedAtMillis = 2,
        ),
      )
      assertTrue(
        repository.replacePasswordHash(
          id = USER_ID,
          expectedHash = "\$2a\$10\$synthetic",
          replacementHash = "modern-hash",
          updatedAtMillis = 2,
        ),
      )

      val updated = requireNotNull(repository.findByIdOrNull(USER_ID))
      assertEquals("modern-hash", updated.passwordHash)
      assertEquals(2, updated.updatedAtMillis)
    }
  }

  @Test
  fun `allows exactly one concurrent initial claim`() {
    XoboroDatabase.open(
      DatabaseConfig(
        path = tempDirectory.resolve("claim.sqlite"),
        maximumPoolSize = 4,
      ),
    ).use { database ->
      val repository = JooqUserRepository(database)
      val ready = CountDownLatch(2)
      val start = CountDownLatch(1)
      val executor = Executors.newFixedThreadPool(2)
      try {
        val results =
          listOf(
            userFixture(
              id = UserId("user-1"),
              email = "first@example.invalid",
              sharedLibraryIds = emptySet(),
            ),
            userFixture(
              id = UserId("user-2"),
              email = "second@example.invalid",
              sharedLibraryIds = emptySet(),
            ),
          ).map { user ->
            executor.submit<Boolean> {
              ready.countDown()
              start.await()
              repository.claimIfEmpty(user)
            }
          }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()

        assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        assertEquals(1, repository.count())
      } finally {
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun `deleting a user cascades every relation`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("delete.sqlite"))).use { database ->
      insertLibrary(database)
      val repository = JooqUserRepository(database)
      repository.insert(userFixture())

      repository.delete(USER_ID)

      assertNull(repository.findByIdOrNull(USER_ID))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("user_role")))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("user_library_sharing")))
      assertEquals(0, database.dsl.fetchCount(org.jooq.impl.DSL.table("user_sharing_label")))
    }
  }

  private fun insertLibrary(database: XoboroDatabase) {
    JooqLibraryRepository(database).insert(
      Library(
        id = LIBRARY_ID,
        name = "Synthetic library",
        root = SourceLocation("local", "file:///synthetic/library"),
        createdAtMillis = 1,
      ),
    )
  }

  private fun userFixture(
    id: UserId = USER_ID,
    email: String = "reader@example.invalid",
    sharedLibraryIds: Set<LibraryId> = setOf(LIBRARY_ID),
  ): User =
    User(
      id = id,
      email = email,
      passwordHash = "\$2a\$10\$synthetic",
      roles = setOf(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING),
      sharedLibraryIds = sharedLibraryIds,
      sharesAllLibraries = false,
      restrictions =
        ContentRestrictions(
          ageRestriction = AgeRestriction(16, RestrictionMode.EXCLUDE),
          labelsAllow = setOf("family"),
          labelsExclude = setOf("restricted"),
        ),
      createdAtMillis = 1,
    )

  companion object {
    private val USER_ID = UserId("user-1")
    private val LIBRARY_ID = LibraryId("library-1")
  }
}
