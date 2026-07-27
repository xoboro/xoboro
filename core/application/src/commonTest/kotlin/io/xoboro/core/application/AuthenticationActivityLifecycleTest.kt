package io.xoboro.core.application

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivityRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthenticationActivityLifecycleTest {
  private val user =
    User(
      id = UserId("user-1"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-hash",
      createdAtMillis = 1,
    )
  private val apiKey =
    ApiKey(
      id = ApiKeyId("key-1"),
      userId = user.id,
      keyHash = "synthetic-key-hash",
      comment = "Synthetic client",
      createdAtMillis = 1,
    )

  @Test
  fun `records successful password and API key activity without credentials`() {
    val repository = InMemoryAuthenticationActivityRepository()
    val lifecycle = AuthenticationActivityLifecycle(repository) { 2_000 }

    lifecycle.recordSuccess(
      user = user,
      source = "Password",
      details = AuthenticationRequestDetails(ip = "192.0.2.1", userAgent = "Synthetic/1.0"),
    )
    lifecycle.recordSuccess(
      user = user,
      source = "ApiKey",
      details = AuthenticationRequestDetails(ip = "192.0.2.2"),
      apiKey = apiKey,
    )

    assertEquals(2, repository.activities.size)
    assertNull(repository.activities.first().apiKeyId)
    with(repository.activities.last()) {
      assertEquals(user.id, userId)
      assertEquals(apiKey.id, apiKeyId)
      assertEquals(apiKey.comment, apiKeyComment)
      assertEquals("ApiKey", source)
      assertEquals(true, success)
      assertEquals(2_000, dateTimeMillis)
    }
  }

  @Test
  fun `records only a fingerprint for failed API key authentication`() {
    val repository = InMemoryAuthenticationActivityRepository()
    val lifecycle = AuthenticationActivityLifecycle(repository) { 3_000 }

    lifecycle.recordFailure(
      source = "ApiKey",
      details = AuthenticationRequestDetails(userAgent = "Synthetic/2.0"),
      error = "Bad credentials",
      apiKeyFingerprint = "irreversible-fingerprint",
    )

    with(repository.activities.single()) {
      assertEquals(false, success)
      assertEquals("irreversible-fingerprint", apiKeyComment)
      assertNull(apiKeyId)
      assertNull(email)
    }
  }

  private class InMemoryAuthenticationActivityRepository : AuthenticationActivityRepository {
    val activities = mutableListOf<AuthenticationActivity>()

    override fun findAll(request: AuthenticationActivityPageRequest): AuthenticationActivityPage =
      AuthenticationActivityPage(activities.toList(), activities.size.toLong(), request)

    override fun findAllByUser(
      user: User,
      request: AuthenticationActivityPageRequest,
    ): AuthenticationActivityPage {
      val matches = activities.filter { it.userId == user.id || it.email == user.email }
      return AuthenticationActivityPage(matches, matches.size.toLong(), request)
    }

    override fun findMostRecentByUser(
      user: User,
      apiKeyId: ApiKeyId?,
    ): AuthenticationActivity? =
      activities
        .filter { (it.userId == user.id || it.email == user.email) && (apiKeyId == null || it.apiKeyId == apiKeyId) }
        .maxByOrNull(AuthenticationActivity::dateTimeMillis)

    override fun insert(activity: AuthenticationActivity) {
      activities += activity
    }

    override fun deleteOlderThan(dateTimeMillis: Long): Int {
      val before = activities.size
      activities.removeAll { it.dateTimeMillis < dateTimeMillis }
      return before - activities.size
    }
  }
}
