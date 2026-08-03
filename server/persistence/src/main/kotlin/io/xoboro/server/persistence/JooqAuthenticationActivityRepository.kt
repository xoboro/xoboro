package io.xoboro.server.persistence

import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import java.util.logging.Level
import java.util.logging.Logger
import org.jooq.exception.DataAccessException
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivityRepository
import io.xoboro.core.domain.AuthenticationActivitySortField
import io.xoboro.core.domain.SortDirection
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import org.jooq.Record

class JooqAuthenticationActivityRepository(
  private val database: XoboroDatabase,
) : AuthenticationActivityRepository {
  override fun findAll(request: AuthenticationActivityPageRequest): AuthenticationActivityPage =
    findAllWhere(
      whereClause = "",
      bindings = emptyArray(),
      request = request,
    )

  override fun findAllByUser(
    user: User,
    request: AuthenticationActivityPageRequest,
  ): AuthenticationActivityPage =
    findAllWhere(
      whereClause = "WHERE user_id = ? OR email = ? COLLATE NOCASE",
      bindings = arrayOf(user.id.value, user.email),
      request = request,
    )

  override fun findMostRecentByUser(
    user: User,
    apiKeyId: ApiKeyId?,
  ): AuthenticationActivity? {
    val apiKeyCondition = if (apiKeyId == null) "" else "AND api_key_id = ?"
    val bindings =
      if (apiKeyId == null) {
        arrayOf(user.id.value, user.email)
      } else {
        arrayOf(user.id.value, user.email, apiKeyId.value)
      }
    return database.dsl
      .fetch(
        """
        $SELECT_ACTIVITY
        WHERE (user_id = ? OR email = ? COLLATE NOCASE)
        $apiKeyCondition
        ORDER BY date_time_ms DESC, sequence_id DESC
        LIMIT 1
        """.trimIndent(),
        *bindings,
      ).map { it.toAuthenticationActivity() }
      .singleOrNull()
  }

  /**
   * Records an authentication attempt, and never fails the caller if it cannot.
   *
   * This is bookkeeping around a request whose real work has already happened, so a locked
   * database must not turn a successful login into a `500` - which is what it did against a real
   * library while a scan held the write lock. The record is dropped instead, and the drop is
   * logged at `WARNING` so it is visible rather than silent: authentication activity is what an
   * administrator reads to spot an attack, and a gap in it that nothing announced would be worse
   * than the gap itself. Only lock contention is absorbed; any other failure still propagates.
   */
  override fun insert(activity: AuthenticationActivity) {
    try {
      insertRecord(activity)
    } catch (failure: DataAccessException) {
      if (!failure.isSqliteContention()) throw failure
      logger.log(
        Level.WARNING,
        "Dropped an authentication activity record because the database was locked: " +
          "source=${activity.source} success=${activity.success}",
      )
    }
  }

  private fun insertRecord(activity: AuthenticationActivity) {
    database.dsl.execute(
      """
      INSERT INTO authentication_activity (
        user_id, email, api_key_id, api_key_comment, ip, user_agent,
        success, error, date_time_ms, source
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      activity.userId?.value,
      activity.email,
      activity.apiKeyId?.value,
      activity.apiKeyComment,
      activity.ip,
      activity.userAgent,
      if (activity.success) 1 else 0,
      activity.error,
      activity.dateTimeMillis,
      activity.source,
    )
  }

  override fun deleteOlderThan(dateTimeMillis: Long): Int =
    database.dsl.execute(
      "DELETE FROM authentication_activity WHERE date_time_ms < ?",
      dateTimeMillis,
    )

  private fun findAllWhere(
    whereClause: String,
    bindings: Array<Any?>,
    request: AuthenticationActivityPageRequest,
  ): AuthenticationActivityPage {
    val total =
      database.dsl
        .fetchOne(
          "SELECT count(*) AS activity_count FROM authentication_activity $whereClause",
          *bindings,
        )?.get("activity_count", Long::class.java)
        ?: 0L
    val orderBy =
      "${request.sortField.columnName} ${request.sortDirection.sql}, " +
        "sequence_id ${request.sortDirection.sql}"
    val pagination = if (request.unpaged) "" else "LIMIT ? OFFSET ?"
    val pageBindings =
      if (request.unpaged) {
        bindings
      } else {
        (bindings.toList() + listOf(request.pageSize, request.offset)).toTypedArray()
      }
    val content =
      database.dsl
        .fetch(
          "$SELECT_ACTIVITY $whereClause ORDER BY $orderBy $pagination",
          *pageBindings,
        ).map { it.toAuthenticationActivity() }
    return AuthenticationActivityPage(content, total, request)
  }

  private fun Record.toAuthenticationActivity(): AuthenticationActivity =
    AuthenticationActivity(
      userId = get("user_id", String::class.java)?.let(::UserId),
      email = get("email", String::class.java),
      apiKeyId = get("api_key_id", String::class.java)?.let(::ApiKeyId),
      apiKeyComment = get("api_key_comment", String::class.java),
      ip = get("ip", String::class.java),
      userAgent = get("user_agent", String::class.java),
      success = requiredBoolean("success"),
      error = get("error", String::class.java),
      dateTimeMillis = requiredLongText("date_time_ms_64"),
      source = get("source", String::class.java),
    )

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requireNotNull(get(field, Int::class.java))) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be a boolean, was $value")
    }

  private val AuthenticationActivitySortField.columnName: String
    get() =
      when (this) {
        AuthenticationActivitySortField.DATE_TIME -> "date_time_ms"
        AuthenticationActivitySortField.EMAIL -> "email"
        AuthenticationActivitySortField.SUCCESS -> "success"
        AuthenticationActivitySortField.IP -> "ip"
        AuthenticationActivitySortField.ERROR -> "error"
        AuthenticationActivitySortField.USER_ID -> "user_id"
        AuthenticationActivitySortField.USER_AGENT -> "user_agent"
      }

  private val SortDirection.sql: String
    get() =
      when (this) {
        SortDirection.ASCENDING -> "ASC"
        SortDirection.DESCENDING -> "DESC"
      }

  companion object {
    private val logger = Logger.getLogger(JooqAuthenticationActivityRepository::class.java.name)

    private const val SELECT_ACTIVITY =
      """
      SELECT authentication_activity.*,
        CAST(date_time_ms AS TEXT) AS date_time_ms_64
      FROM authentication_activity
      """
  }
}
