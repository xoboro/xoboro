package io.xoboro.core.application

import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.ServerAlreadyClaimedException
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole

interface PasswordHasher {
  fun hash(rawPassword: String): String

  fun matches(
    rawPassword: String,
    passwordHash: String,
  ): Boolean
}

class UserLifecycle(
  private val users: UserRepository,
  private val passwordHasher: PasswordHasher,
  private val userIdFactory: () -> String,
  private val currentTimeMillis: () -> Long,
) {
  fun isClaimed(): Boolean = users.count() > 0

  fun claimInitialAdministrator(
    email: String,
    rawPassword: String,
  ): User {
    require(rawPassword.isNotBlank()) { "User password must not be blank" }
    val nowMillis = now()
    val user =
      User(
        id = UserId(userIdFactory()),
        email = email,
        passwordHash = passwordHasher.hash(rawPassword),
        roles = UserRole.entries.toSet(),
        createdAtMillis = nowMillis,
      )
    if (!users.claimIfEmpty(user)) throw ServerAlreadyClaimedException()
    return requireNotNull(users.findByIdOrNull(user.id))
  }

  fun createUser(
    email: String,
    rawPassword: String,
    roles: Set<UserRole> = setOf(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING),
    sharedLibraryIds: Set<LibraryId> = emptySet(),
    sharesAllLibraries: Boolean = true,
    restrictions: ContentRestrictions = ContentRestrictions(),
  ): User {
    require(rawPassword.isNotBlank()) { "User password must not be blank" }
    if (users.findByEmailIgnoreCaseOrNull(email) != null) {
      throw UserEmailAlreadyExistsException(email)
    }
    val nowMillis = now()
    val user =
      User(
        id = UserId(userIdFactory()),
        email = email,
        passwordHash = passwordHasher.hash(rawPassword),
        roles = roles,
        sharedLibraryIds = sharedLibraryIds,
        sharesAllLibraries = sharesAllLibraries,
        restrictions = restrictions,
        createdAtMillis = nowMillis,
      )
    try {
      users.insert(user)
    } catch (_: UserEmailAlreadyExistsException) {
      throw UserEmailAlreadyExistsException(email)
    }
    return requireNotNull(users.findByIdOrNull(user.id))
  }

  fun findAll(): List<User> = users.findAll()

  fun findByIdOrNull(id: UserId): User? = users.findByIdOrNull(id)

  fun updateUser(user: User): User {
    requireNotNull(users.findByIdOrNull(user.id)) { "User not found: ${user.id.value}" }
    val updated = user.copy(updatedAtMillis = now())
    users.update(updated)
    return requireNotNull(users.findByIdOrNull(user.id))
  }

  fun updatePassword(
    id: UserId,
    rawPassword: String,
  ): User {
    require(rawPassword.isNotBlank()) { "User password must not be blank" }
    val existing = requireNotNull(users.findByIdOrNull(id)) { "User not found: ${id.value}" }
    val updated =
      existing.copy(
        passwordHash = passwordHasher.hash(rawPassword),
        updatedAtMillis = now(),
      )
    users.update(updated)
    return requireNotNull(users.findByIdOrNull(id))
  }

  fun deleteUser(id: UserId): Boolean {
    if (users.findByIdOrNull(id) == null) return false
    users.delete(id)
    return true
  }

  fun authenticate(
    email: String,
    rawPassword: String,
  ): User? {
    if (email.isBlank() || rawPassword.isBlank()) return null
    val user = users.findByEmailIgnoreCaseOrNull(email) ?: return null
    return user.takeIf { passwordHasher.matches(rawPassword, user.passwordHash) }
  }

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "User lifecycle timestamp must not be negative" }
    }
}
