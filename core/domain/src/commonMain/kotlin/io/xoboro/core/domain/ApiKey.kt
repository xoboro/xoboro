package io.xoboro.core.domain

data class ApiKeyId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "API key ID must not be blank" }
  }
}

data class ApiKey(
  val id: ApiKeyId,
  val userId: UserId,
  val keyHash: String,
  val comment: String,
  val scopes: Set<UserRole> = emptySet(),
  val expiresAtMillis: Long? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(keyHash.isNotBlank()) { "API key hash must not be blank" }
    require(comment.isNotBlank() && comment == comment.trim()) {
      "API key comment must be trimmed and non-blank"
    }
    require(createdAtMillis >= 0) { "Created timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Updated timestamp must not precede created timestamp"
    }
    require(expiresAtMillis == null || expiresAtMillis > createdAtMillis) {
      "API key expiry must be after the created timestamp"
    }
  }

  /** True once [atMillis] has reached the expiry. An unscoped-in-time key never expires. */
  fun hasExpired(atMillis: Long): Boolean =
    expiresAtMillis != null && atMillis >= expiresAtMillis

  /**
   * Narrows [user] to the capabilities this key is allowed to exercise.
   *
   * Scoping is expressed as a subset of [UserRole] rather than as a separate permission vocabulary,
   * because roles are already what every interface checks. Projecting the key onto the caller's
   * `User` means the scope is enforced by the role checks and the
   * [io.xoboro.core.application.catalogAccess] projection that already exist, at one place, instead
   * of by a second set of checks that each route would have to remember to run. This is the same
   * reason the access projection was consolidated in ADR 0090.
   *
   * The result is an **intersection**, so a key whose scopes name a role its owner does not hold
   * grants nothing extra — a stored scope can only ever remove capability. That matters because the
   * owner's roles can be reduced after the key was issued, and the key must follow.
   *
   * An empty scope set means "whatever the owner currently holds". Keys created before scoping
   * existed read back that way, so they keep behaving exactly as they did.
   */
  fun scope(user: User): User =
    if (scopes.isEmpty()) user else user.copy(roles = user.roles intersect scopes)
}

interface ApiKeyRepository {
  fun findByKeyHashOrNull(keyHash: String): ApiKey?

  fun findAllByUserId(userId: UserId): List<ApiKey>

  fun existsByCommentIgnoreCase(
    userId: UserId,
    comment: String,
  ): Boolean

  fun insert(apiKey: ApiKey)

  fun deleteByIdAndUserId(
    id: ApiKeyId,
    userId: UserId,
  ): Boolean
}

class ApiKeyCommentAlreadyExistsException(
  comment: String,
) : IllegalArgumentException("API key comment already exists for this user: $comment")

class ApiKeyHashAlreadyExistsException : IllegalStateException("API key hash already exists")
