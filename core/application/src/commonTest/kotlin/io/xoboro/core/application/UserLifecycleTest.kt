package io.xoboro.core.application

import io.xoboro.core.domain.ServerAlreadyClaimedException
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserLifecycleTest {
  @Test
  fun `claims an empty server with every Komga role and a password hash`() {
    val repository = InMemoryUserRepository()
    val lifecycle = lifecycle(repository)

    val claimed =
      lifecycle.claimInitialAdministrator(
        email = "admin@example.invalid",
        rawPassword = "synthetic-password",
      )

    assertEquals(UserRole.entries.toSet(), claimed.roles)
    assertEquals("hashed:synthetic-password", claimed.passwordHash)
    assertEquals(100, claimed.createdAtMillis)
    assertTrue(lifecycle.authenticate("ADMIN@example.invalid", "synthetic-password") != null)
  }

  @Test
  fun `claim is atomic and cannot replace the existing administrator`() {
    val repository = InMemoryUserRepository()
    val lifecycle = lifecycle(repository)
    lifecycle.claimInitialAdministrator("first@example.invalid", "first-password")

    assertFailsWith<ServerAlreadyClaimedException> {
      lifecycle.claimInitialAdministrator("second@example.invalid", "second-password")
    }

    assertEquals(1, repository.count())
    assertEquals("first@example.invalid", repository.findAll().single().email)
  }

  @Test
  fun `creates default readers and rejects case-insensitive duplicate emails`() {
    val repository = InMemoryUserRepository()
    val lifecycle = lifecycle(repository)
    val created = lifecycle.createUser("reader@example.invalid", "reader-password")

    assertEquals(setOf(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING), created.roles)
    assertFailsWith<UserEmailAlreadyExistsException> {
      lifecycle.createUser("READER@example.invalid", "other-password")
    }
  }

  @Test
  fun `authentication rejects missing users blank values and wrong passwords`() {
    val repository = InMemoryUserRepository()
    val lifecycle = lifecycle(repository)
    lifecycle.createUser("reader@example.invalid", "reader-password")

    assertNull(lifecycle.authenticate("", "reader-password"))
    assertNull(lifecycle.authenticate("missing@example.invalid", "reader-password"))
    assertNull(lifecycle.authenticate("reader@example.invalid", "wrong-password"))
  }

  private fun lifecycle(repository: InMemoryUserRepository): UserLifecycle =
    UserLifecycle(
      users = repository,
      passwordHasher =
        object : PasswordHasher {
          override fun hash(rawPassword: String): String = "hashed:$rawPassword"

          override fun matches(
            rawPassword: String,
            passwordHash: String,
          ): Boolean = passwordHash == "hashed:$rawPassword"
        },
      userIdFactory = { "user-${repository.count() + 1}" },
      currentTimeMillis = { 100 },
    )

  private class InMemoryUserRepository : UserRepository {
    private val users = linkedMapOf<UserId, User>()

    override fun count(): Long = users.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = users[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = users.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      users[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (users.isNotEmpty()) return false
      insert(user)
      return true
    }

    override fun update(user: User) {
      users[user.id] = user
    }

    override fun delete(id: UserId) {
      users.remove(id)
    }
  }
}
