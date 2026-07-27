package io.xoboro.core.application

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.ApiKeyRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository

fun interface TokenEncoder {
  fun encode(rawToken: String): String
}

data class CreatedApiKey(
  val apiKey: ApiKey,
  val plainTextKey: String,
)

data class ApiKeyPrincipal(
  val user: User,
  val apiKey: ApiKey,
)

class ApiKeyLifecycle(
  private val users: UserRepository,
  private val apiKeys: ApiKeyRepository,
  private val tokenEncoder: TokenEncoder,
  private val apiKeyIdFactory: () -> String,
  private val plainTextKeyFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) {
  fun create(
    userId: UserId,
    comment: String,
  ): CreatedApiKey? {
    requireNotNull(users.findByIdOrNull(userId)) { "User not found: ${userId.value}" }
    val normalizedComment = comment.trim()
    require(normalizedComment.isNotBlank()) { "API key comment must not be blank" }
    if (apiKeys.existsByCommentIgnoreCase(userId, normalizedComment)) {
      throw ApiKeyCommentAlreadyExistsException(normalizedComment)
    }
    repeat(MAX_GENERATION_ATTEMPTS) {
      val plainTextKey = plainTextKeyFactory()
      require(plainTextKey.isNotBlank()) { "Generated API key must not be blank" }
      val nowMillis = now()
      val apiKey =
        ApiKey(
          id = ApiKeyId(apiKeyIdFactory()),
          userId = userId,
          keyHash = tokenEncoder.encode(plainTextKey),
          comment = normalizedComment,
          createdAtMillis = nowMillis,
        )
      try {
        apiKeys.insert(apiKey)
        return CreatedApiKey(apiKey, plainTextKey)
      } catch (_: ApiKeyHashAlreadyExistsException) {
        // Generate another token without changing the externally visible contract.
      }
    }
    return null
  }

  fun findAll(userId: UserId): List<ApiKey> = apiKeys.findAllByUserId(userId)

  fun delete(
    userId: UserId,
    apiKeyId: ApiKeyId,
  ): Boolean = apiKeys.deleteByIdAndUserId(apiKeyId, userId)

  fun authenticate(rawToken: String): ApiKeyPrincipal? {
    if (rawToken.isBlank()) return null
    val apiKey = apiKeys.findByKeyHashOrNull(tokenEncoder.encode(rawToken)) ?: return null
    val user = users.findByIdOrNull(apiKey.userId) ?: return null
    return ApiKeyPrincipal(user, apiKey)
  }

  fun fingerprint(rawToken: String): String = tokenEncoder.encode(rawToken)

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "API key lifecycle timestamp must not be negative" }
    }

  companion object {
    const val MAX_GENERATION_ATTEMPTS: Int = 10
  }
}
