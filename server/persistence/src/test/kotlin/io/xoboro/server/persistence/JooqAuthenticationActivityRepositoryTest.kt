package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivitySortField
import io.xoboro.core.domain.SortDirection
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRole
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class JooqAuthenticationActivityRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `persists pages filters and cleans authentication activity`() {
    val path = tempDirectory.resolve("authentication-activity.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val users = JooqUserRepository(database)
      val repository = JooqAuthenticationActivityRepository(database)
      val firstUser = syntheticUser("user-1", "first@example.invalid")
      val secondUser = syntheticUser("user-2", "second@example.invalid")
      users.insert(firstUser)
      users.insert(secondUser)
      repository.insert(syntheticActivity(firstUser, 1_000, success = true))
      repository.insert(
        syntheticActivity(
          firstUser,
          3_000,
          success = false,
          apiKeyId = ApiKeyId("key-1"),
        ),
      )
      repository.insert(syntheticActivity(secondUser, 2_000, success = true))

      val descending =
        repository.findAll(
          AuthenticationActivityPageRequest(pageSize = 2),
        )
      assertEquals(3, descending.totalElements)
      assertEquals(listOf(3_000L, 2_000L), descending.content.map { it.dateTimeMillis })

      val ascendingForUser =
        repository.findAllByUser(
          firstUser,
          AuthenticationActivityPageRequest(
            unpaged = true,
            sortField = AuthenticationActivitySortField.DATE_TIME,
            sortDirection = SortDirection.ASCENDING,
          ),
        )
      assertEquals(listOf(1_000L, 3_000L), ascendingForUser.content.map { it.dateTimeMillis })
      assertEquals(
        3_000,
        repository.findMostRecentByUser(firstUser, ApiKeyId("key-1"))?.dateTimeMillis,
      )
      assertEquals(1, repository.deleteOlderThan(2_000))
    }

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqAuthenticationActivityRepository(database)
      assertEquals(2, repository.findAll(AuthenticationActivityPageRequest(unpaged = true)).totalElements)
      JooqUserRepository(database).delete(UserId("user-1"))
      assertNull(repository.findMostRecentByUser(syntheticUser("user-1", "first@example.invalid")))
      assertEquals(1, repository.findAll(AuthenticationActivityPageRequest(unpaged = true)).totalElements)
    }
  }

  private fun syntheticUser(
    id: String,
    email: String,
  ): User =
    User(
      id = UserId(id),
      email = email,
      passwordHash = "synthetic-hash",
      roles = setOf(UserRole.PAGE_STREAMING),
      createdAtMillis = 1,
    )

  private fun syntheticActivity(
    user: User,
    dateTimeMillis: Long,
    success: Boolean,
    apiKeyId: ApiKeyId? = null,
  ): AuthenticationActivity =
    AuthenticationActivity(
      userId = user.id,
      email = user.email,
      apiKeyId = apiKeyId,
      apiKeyComment = apiKeyId?.let { "Synthetic client" },
      ip = "192.0.2.10",
      userAgent = "Synthetic/1.0",
      success = success,
      error = if (success) null else "Bad credentials",
      dateTimeMillis = dateTimeMillis,
      source = if (apiKeyId == null) "Password" else "ApiKey",
    )
}
