package io.xoboro.core.application

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivityRepository
import io.xoboro.core.domain.User

data class AuthenticationRequestDetails(
  val ip: String? = null,
  val userAgent: String? = null,
)

class AuthenticationActivityLifecycle(
  private val activities: AuthenticationActivityRepository,
  private val currentTimeMillis: () -> Long,
) {
  fun recordSuccess(
    user: User,
    source: String,
    details: AuthenticationRequestDetails,
    apiKey: ApiKey? = null,
  ) {
    activities.insert(
      AuthenticationActivity(
        userId = user.id,
        email = user.email,
        apiKeyId = apiKey?.id,
        apiKeyComment = apiKey?.comment,
        ip = details.ip,
        userAgent = details.userAgent,
        success = true,
        dateTimeMillis = now(),
        source = source,
      ),
    )
  }

  fun recordFailure(
    source: String,
    details: AuthenticationRequestDetails,
    error: String,
    user: User? = null,
    email: String? = null,
    apiKeyFingerprint: String? = null,
  ) {
    activities.insert(
      AuthenticationActivity(
        userId = user?.id,
        email = email,
        apiKeyComment = apiKeyFingerprint,
        ip = details.ip,
        userAgent = details.userAgent,
        success = false,
        error = error,
        dateTimeMillis = now(),
        source = source,
      ),
    )
  }

  fun findAll(request: AuthenticationActivityPageRequest): AuthenticationActivityPage =
    activities.findAll(request)

  fun findAllByUser(
    user: User,
    request: AuthenticationActivityPageRequest,
  ): AuthenticationActivityPage = activities.findAllByUser(user, request)

  fun findMostRecentByUser(
    user: User,
    apiKeyId: ApiKeyId? = null,
  ): AuthenticationActivity? = activities.findMostRecentByUser(user, apiKeyId)

  fun deleteOlderThan(dateTimeMillis: Long): Int {
    require(dateTimeMillis >= 0) { "Authentication activity cutoff must not be negative" }
    return activities.deleteOlderThan(dateTimeMillis)
  }

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "Authentication activity timestamp must not be negative" }
    }
}
