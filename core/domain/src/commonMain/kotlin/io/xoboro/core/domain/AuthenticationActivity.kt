package io.xoboro.core.domain

data class AuthenticationActivity(
  val userId: UserId? = null,
  val email: String? = null,
  val apiKeyId: ApiKeyId? = null,
  val apiKeyComment: String? = null,
  val ip: String? = null,
  val userAgent: String? = null,
  val success: Boolean,
  val error: String? = null,
  val dateTimeMillis: Long,
  val source: String? = null,
) {
  init {
    require(dateTimeMillis >= 0) { "Authentication activity timestamp must not be negative" }
  }
}

enum class AuthenticationActivitySortField {
  DATE_TIME,
  EMAIL,
  SUCCESS,
  IP,
  ERROR,
  USER_ID,
  USER_AGENT,
}

enum class SortDirection {
  ASCENDING,
  DESCENDING,
}

data class AuthenticationActivityPageRequest(
  val pageNumber: Int = 0,
  val pageSize: Int = 20,
  val unpaged: Boolean = false,
  val sortField: AuthenticationActivitySortField = AuthenticationActivitySortField.DATE_TIME,
  val sortDirection: SortDirection = SortDirection.DESCENDING,
) {
  init {
    require(pageNumber >= 0) { "Page number must not be negative" }
    require(pageSize > 0) { "Page size must be positive" }
  }

  val offset: Long
    get() = if (unpaged) 0 else pageNumber.toLong() * pageSize
}

data class AuthenticationActivityPage(
  val content: List<AuthenticationActivity>,
  val totalElements: Long,
  val request: AuthenticationActivityPageRequest,
) {
  init {
    require(totalElements >= 0) { "Total elements must not be negative" }
  }
}

interface AuthenticationActivityRepository {
  fun findAll(request: AuthenticationActivityPageRequest): AuthenticationActivityPage

  fun findAllByUser(
    user: User,
    request: AuthenticationActivityPageRequest,
  ): AuthenticationActivityPage

  fun findMostRecentByUser(
    user: User,
    apiKeyId: ApiKeyId? = null,
  ): AuthenticationActivity?

  fun insert(activity: AuthenticationActivity)

  fun deleteOlderThan(dateTimeMillis: Long): Int
}
