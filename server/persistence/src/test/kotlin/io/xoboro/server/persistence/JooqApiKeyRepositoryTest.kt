package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqApiKeyRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `round trips keys and enforces owner-scoped deletion across restart`() {
    val path = tempDirectory.resolve("api-keys.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      JooqUserRepository(database).insert(user())
      val repository = JooqApiKeyRepository(database)
      repository.insert(apiKey())

      assertEquals(apiKey(), repository.findByKeyHashOrNull("synthetic-hash"))
      assertEquals(listOf(apiKey()), repository.findAllByUserId(USER_ID))
      assertEquals(false, repository.deleteByIdAndUserId(API_KEY_ID, UserId("other-user")))
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqApiKeyRepository(database)
      assertEquals(true, repository.deleteByIdAndUserId(API_KEY_ID, USER_ID))
      assertNull(repository.findByKeyHashOrNull("synthetic-hash"))
    }
  }

  @Test
  fun `maps case-insensitive comment and hash conflicts`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("conflicts.sqlite"))).use { database ->
      JooqUserRepository(database).insert(user())
      val repository = JooqApiKeyRepository(database)
      repository.insert(apiKey())

      assertFailsWith<ApiKeyCommentAlreadyExistsException> {
        repository.insert(
          apiKey().copy(
            id = ApiKeyId("key-2"),
            keyHash = "other-hash",
            comment = "SYNTHETIC CLIENT",
          ),
        )
      }
      assertFailsWith<ApiKeyHashAlreadyExistsException> {
        repository.insert(
          apiKey().copy(
            id = ApiKeyId("key-3"),
            comment = "Other client",
          ),
        )
      }
    }
  }

  @Test
  fun `deleting a user cascades API keys`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("cascade.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      val apiKeys = JooqApiKeyRepository(database)
      users.insert(user())
      apiKeys.insert(apiKey())

      users.delete(USER_ID)

      assertNull(apiKeys.findByKeyHashOrNull("synthetic-hash"))
    }
  }

  private fun user(): User =
    User(
      id = USER_ID,
      email = "reader@example.invalid",
      passwordHash = "synthetic-password-hash",
      createdAtMillis = 1,
    )

  private fun apiKey(): ApiKey =
    ApiKey(
      id = API_KEY_ID,
      userId = USER_ID,
      keyHash = "synthetic-hash",
      comment = "Synthetic client",
      createdAtMillis = 2,
    )

  private companion object {
    val USER_ID = UserId("user-1")
    val API_KEY_ID = ApiKeyId("key-1")
  }
}
