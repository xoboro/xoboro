package io.xoboro.server.persistence

import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.exception.DataAccessException

class JooqUserRepository(
  private val database: XoboroDatabase,
) : UserRepository {
  override fun count(): Long =
    database.dsl
      .fetchOne("SELECT count(*) FROM user_account")
      ?.get(0)
      ?.let { it as Number }
      ?.toLong()
      ?: 0

  override fun findByIdOrNull(id: UserId): User? =
    database.dsl
      .fetch("$SELECT_USER WHERE id = ?", id.value)
      .toUsers()
      .singleOrNull()

  override fun findByEmailIgnoreCaseOrNull(email: String): User? =
    database.dsl
      .fetch("$SELECT_USER WHERE email = ? COLLATE NOCASE", email)
      .toUsers()
      .singleOrNull()

  override fun findAll(): List<User> =
    database.dsl
      .fetch("$SELECT_USER ORDER BY email COLLATE NOCASE, id")
      .toUsers()

  override fun insert(user: User) {
    try {
      database.transaction { transaction ->
        transaction.insertUser(user)
        transaction.insertRelations(user)
      }
    } catch (failure: DataAccessException) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      throw failure
    }
  }

  override fun claimIfEmpty(user: User): Boolean =
    database.transaction { transaction ->
      val inserted =
        transaction.execute(
          """
          INSERT INTO user_account (
            id, email, password_hash, shares_all_libraries, age_restriction,
            age_restriction_mode, created_at_ms, updated_at_ms
          )
          SELECT ?, ?, ?, ?, ?, ?, ?, ?
          WHERE NOT EXISTS (SELECT 1 FROM user_account)
          """.trimIndent(),
          *user.bindValues(),
        ) == 1
      if (inserted) transaction.insertRelations(user)
      inserted
    }

  override fun update(user: User) {
    try {
      database.transaction { transaction ->
        val affected =
          transaction.execute(
            """
            UPDATE user_account SET
              email = ?, password_hash = ?, shares_all_libraries = ?,
              age_restriction = ?, age_restriction_mode = ?, updated_at_ms = ?
            WHERE id = ?
            """.trimIndent(),
            user.email,
            user.passwordHash,
            user.sharesAllLibraries.toSqliteInt(),
            user.restrictions.ageRestriction?.age,
            user.restrictions.ageRestriction?.mode?.name,
            user.updatedAtMillis,
            user.id.value,
          )
        if (affected == 0) throw NoSuchElementException("User not found: ${user.id.value}")
        transaction.deleteRelations(user.id)
        transaction.insertRelations(user)
      }
    } catch (failure: DataAccessException) {
      val conflicting = findByEmailIgnoreCaseOrNull(user.email)
      if (conflicting != null && conflicting.id != user.id) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      throw failure
    }
  }

  override fun delete(id: UserId) {
    database.dsl.execute("DELETE FROM user_account WHERE id = ?", id.value)
  }

  private fun DSLContext.insertUser(user: User) {
    execute(
      """
      INSERT INTO user_account (
        id, email, password_hash, shares_all_libraries, age_restriction,
        age_restriction_mode, created_at_ms, updated_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      *user.bindValues(),
    )
  }

  private fun User.bindValues(): Array<Any?> =
    arrayOf(
      id.value,
      email,
      passwordHash,
      sharesAllLibraries.toSqliteInt(),
      restrictions.ageRestriction?.age,
      restrictions.ageRestriction?.mode?.name,
      createdAtMillis,
      updatedAtMillis,
    )

  private fun DSLContext.insertRelations(user: User) {
    user.roles.sortedBy(UserRole::name).forEach { role ->
      execute(
        "INSERT INTO user_role (user_id, role) VALUES (?, ?)",
        user.id.value,
        role.name,
      )
    }
    user.sharedLibraryIds.sortedBy(LibraryId::value).forEach { libraryId ->
      execute(
        "INSERT INTO user_library_sharing (user_id, library_id) VALUES (?, ?)",
        user.id.value,
        libraryId.value,
      )
    }
    user.restrictions.labelsAllow.sorted().forEach { label ->
      insertSharingLabel(user.id, label, allow = true)
    }
    user.restrictions.labelsExclude.sorted().forEach { label ->
      insertSharingLabel(user.id, label, allow = false)
    }
  }

  private fun DSLContext.insertSharingLabel(
    userId: UserId,
    label: String,
    allow: Boolean,
  ) {
    execute(
      "INSERT INTO user_sharing_label (user_id, label, allow) VALUES (?, ?, ?)",
      userId.value,
      label,
      allow.toSqliteInt(),
    )
  }

  private fun DSLContext.deleteRelations(userId: UserId) {
    execute("DELETE FROM user_role WHERE user_id = ?", userId.value)
    execute("DELETE FROM user_library_sharing WHERE user_id = ?", userId.value)
    execute("DELETE FROM user_sharing_label WHERE user_id = ?", userId.value)
  }

  private fun List<Record>.toUsers(): List<User> {
    if (isEmpty()) return emptyList()
    val userIds = map { UserId(it.requiredString("id")) }
    val placeholders = userIds.joinToString(",") { "?" }
    val bindValues = userIds.map(UserId::value).toTypedArray()
    val roles =
      database.dsl
        .fetch(
          "SELECT user_id, role FROM user_role WHERE user_id IN ($placeholders)",
          *bindValues,
        ).groupBy(
          keySelector = { UserId(it.requiredString("user_id")) },
          valueTransform = { UserRole.valueOf(it.requiredString("role")) },
        )
    val libraries =
      database.dsl
        .fetch(
          """
          SELECT user_id, library_id
          FROM user_library_sharing
          WHERE user_id IN ($placeholders)
          """.trimIndent(),
          *bindValues,
        ).groupBy(
          keySelector = { UserId(it.requiredString("user_id")) },
          valueTransform = { LibraryId(it.requiredString("library_id")) },
        )
    val labels =
      database.dsl
        .fetch(
          """
          SELECT user_id, label, allow
          FROM user_sharing_label
          WHERE user_id IN ($placeholders)
          """.trimIndent(),
          *bindValues,
        ).groupBy { UserId(it.requiredString("user_id")) }

    return map { record ->
      val id = UserId(record.requiredString("id"))
      val labelsForUser = labels[id].orEmpty()
      User(
        id = id,
        email = record.requiredString("email"),
        passwordHash = record.requiredString("password_hash"),
        roles = roles[id].orEmpty().toSet(),
        sharedLibraryIds = libraries[id].orEmpty().toSet(),
        sharesAllLibraries = record.requiredBoolean("shares_all_libraries"),
        restrictions =
          ContentRestrictions(
            ageRestriction =
              record.get("age_restriction")?.let { rawAge ->
                AgeRestriction(
                  age = (rawAge as Number).toInt(),
                  mode = RestrictionMode.valueOf(record.requiredString("age_restriction_mode")),
                )
              },
            labelsAllow =
              labelsForUser
                .filter { it.requiredBoolean("allow") }
                .map { it.requiredString("label") }
                .toSet(),
            labelsExclude =
              labelsForUser
                .filterNot { it.requiredBoolean("allow") }
                .map { it.requiredString("label") }
                .toSet(),
          ),
        createdAtMillis = record.requiredLongText("created_at_ms_64"),
        updatedAtMillis = record.requiredLongText("updated_at_ms_64"),
      )
    }
  }

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)) { "Database field '$field' must not be null" }
      .toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    when (val value = requireNotNull(get(field, Int::class.java))) {
      0 -> false
      1 -> true
      else -> error("Database field '$field' must be 0 or 1, got $value")
    }

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0

  companion object {
    private const val SELECT_USER =
      """
      SELECT user_account.*,
        CAST(created_at_ms AS TEXT) AS created_at_ms_64,
        CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
      FROM user_account
      """
  }
}
