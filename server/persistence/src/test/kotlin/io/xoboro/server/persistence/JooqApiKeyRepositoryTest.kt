package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
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

  @Test
  fun `round trips scopes and expiry across restart`() {
    val path = tempDirectory.resolve("scoped-keys.sqlite")
    val scoped =
      apiKey().copy(
        scopes = setOf(UserRole.PAGE_STREAMING, UserRole.KOBO_SYNC),
        expiresAtMillis = 5_000,
      )
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      JooqUserRepository(database).insert(user())
      JooqApiKeyRepository(database).insert(scoped)
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqApiKeyRepository(database)
      // Whole-object equality on the reopened database: this is what catches a scope written to the
      // wrong column, an expiry lost to integer narrowing, or a bind order that silently swapped two
      // values. Asserting field by field would let any of those through.
      assertEquals(scoped, repository.findByKeyHashOrNull("synthetic-hash"))
      assertEquals(listOf(scoped), repository.findAllByUserId(USER_ID))
    }
  }

  @Test
  fun `keeps an unscoped non-expiring key indistinguishable from a pre-scoping row`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("legacy.sqlite"))).use { database ->
      JooqUserRepository(database).insert(user())
      val repository = JooqApiKeyRepository(database)
      repository.insert(apiKey())

      val stored = requireNotNull(repository.findByKeyHashOrNull("synthetic-hash"))
      assertEquals(emptySet(), stored.scopes)
      assertNull(stored.expiresAtMillis)
    }
  }

  @Test
  fun `deleting a key cascades its scopes`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("scope-cascade.sqlite"))).use { database ->
      JooqUserRepository(database).insert(user())
      val repository = JooqApiKeyRepository(database)
      repository.insert(apiKey().copy(scopes = setOf(UserRole.PAGE_STREAMING)))

      assertEquals(true, repository.deleteByIdAndUserId(API_KEY_ID, USER_ID))

      // An orphaned scope row would be reattached to whatever key later reused the id, granting it
      // capabilities nobody asked for. Deterministic key ids make that reachable, not theoretical.
      assertEquals(
        0,
        database.dsl.fetchCount(
          database.dsl.selectOne().from("user_api_key_scope").where("api_key_id = ?", API_KEY_ID.value),
        ),
      )
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
