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
  }
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
