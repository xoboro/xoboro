package io.xoboro.core.application

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.ApiKeyRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole

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
  /**
   * [scopes] narrows what the key may do to a subset of the owner's roles; an empty set means
   * "whatever the owner currently holds". [expiresAtMillis] is an absolute instant, not a duration,
   * so a stored key does not become longer-lived by being read later.
   *
   * A scope naming a role the owner does not hold is rejected rather than silently intersected away.
   * At creation the caller is stating an intent, and an intent that cannot be satisfied is a mistake
   * worth reporting; at authentication the same mismatch is expected — the owner's roles may have
   * been reduced since — and is silently narrowed instead. See [ApiKey.scope].
   */
  fun create(
    userId: UserId,
    comment: String,
    scopes: Set<UserRole> = emptySet(),
    expiresAtMillis: Long? = null,
  ): CreatedApiKey? {
    val owner = requireNotNull(users.findByIdOrNull(userId)) { "User not found: ${userId.value}" }
    val normalizedComment = comment.trim()
    require(normalizedComment.isNotBlank()) { "API key comment must not be blank" }
    require(owner.roles.containsAll(scopes)) {
      "API key scopes must be a subset of the user's roles"
    }
    if (apiKeys.existsByCommentIgnoreCase(userId, normalizedComment)) {
      throw ApiKeyCommentAlreadyExistsException(normalizedComment)
    }
    repeat(MAX_GENERATION_ATTEMPTS) {
      val plainTextKey = plainTextKeyFactory()
      require(plainTextKey.isNotBlank()) { "Generated API key must not be blank" }
      val nowMillis = now()
      require(expiresAtMillis == null || expiresAtMillis > nowMillis) {
        "API key expiry must be in the future"
      }
      val apiKey =
        ApiKey(
          id = ApiKeyId(apiKeyIdFactory()),
          userId = userId,
          keyHash = tokenEncoder.encode(plainTextKey),
          comment = normalizedComment,
          scopes = scopes,
          expiresAtMillis = expiresAtMillis,
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

  /**
   * Resolves a raw token to the caller it authorizes, or null when it authorizes nobody.
   *
   * The returned principal's `user` is already narrowed to the key's scopes, which is what makes
   * scoping enforceable: every role check and every `catalogAccess()` projection downstream reads
   * that `User`, so none of them has to know a key was involved. A route cannot forget to apply a
   * scope it never sees.
   *
   * An expired key is rejected here rather than swept by a background job. Deleting rows on a
   * schedule would make expiry depend on that job having run, and a key whose expiry has passed must
   * stop working at the instant it passes, not at the next sweep.
   */
  fun authenticate(rawToken: String): ApiKeyPrincipal? {
    if (rawToken.isBlank()) return null
    val apiKey = apiKeys.findByKeyHashOrNull(tokenEncoder.encode(rawToken)) ?: return null
    if (apiKey.hasExpired(now())) return null
    val user = users.findByIdOrNull(apiKey.userId) ?: return null
    return ApiKeyPrincipal(apiKey.scope(user), apiKey)
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
