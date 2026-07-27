package io.xoboro.core.application

import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserSession
import io.xoboro.core.domain.UserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSessionLifecycleTest {
  @Test
  fun `creates touches expires invalidates and handles token collisions`() {
    val user = syntheticUser()
    val users = SingleUserRepository(user)
    val sessions = InMemorySessionRepository()
    sessions.insertIfAbsent(session("hash:collision", user.id, expiresAtMillis = 1_000))
    val tokens = listOf("collision", "unique").iterator()
    var now = 100L
    val lifecycle =
      UserSessionLifecycle(
        users = users,
        sessions = sessions,
        tokenEncoder = TokenEncoder { "hash:$it" },
        plainTokenFactory = tokens::next,
        currentTimeMillis = { now },
        inactivityTimeoutMillis = 500,
      )

    val created = lifecycle.create(user)
    assertEquals("unique", created.plainToken)
    assertEquals(user, lifecycle.authenticate("unique"))
    assertEquals(600, sessions.findByTokenDigestOrNull("hash:unique")?.expiresAtMillis)

    now = 599
    assertEquals(user, lifecycle.authenticate("unique"))
    assertEquals(1_099, sessions.findByTokenDigestOrNull("hash:unique")?.expiresAtMillis)

    now = 1_099
    assertNull(lifecycle.authenticate("unique"))
    assertNull(sessions.findByTokenDigestOrNull("hash:unique"))

    now = 1_100
    assertEquals(1, lifecycle.deleteExpired())
    assertEquals(false, lifecycle.invalidate("missing"))
  }

  private fun syntheticUser(): User =
    User(
      id = UserId("user-1"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-hash",
      createdAtMillis = 0,
    )

  private fun session(
    digest: String,
    userId: UserId,
    expiresAtMillis: Long,
  ): UserSession =
    UserSession(
      tokenDigest = digest,
      userId = userId,
      createdAtMillis = 0,
      expiresAtMillis = expiresAtMillis,
    )

  private class SingleUserRepository(
    private var user: User?,
  ) : UserRepository {
    override fun count(): Long = if (user == null) 0 else 1

    override fun findByIdOrNull(id: UserId): User? = user?.takeIf { it.id == id }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      user?.takeIf { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = listOfNotNull(user)

    override fun insert(user: User) {
      if (this.user != null) throw UserEmailAlreadyExistsException(user.email)
      this.user = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (this.user != null) return false
      this.user = user
      return true
    }

    override fun update(user: User) {
      this.user = user
    }

    override fun delete(id: UserId) {
      if (user?.id == id) user = null
    }
  }

  private class InMemorySessionRepository : UserSessionRepository {
    private val sessions = mutableMapOf<String, UserSession>()

    override fun findByTokenDigestOrNull(tokenDigest: String): UserSession? =
      sessions[tokenDigest]

    override fun insertIfAbsent(session: UserSession): Boolean {
      if (session.tokenDigest in sessions) return false
      sessions[session.tokenDigest] = session
      return true
    }

    override fun update(session: UserSession) {
      if (session.tokenDigest in sessions) sessions[session.tokenDigest] = session
    }

    override fun deleteByTokenDigest(tokenDigest: String): Boolean =
      sessions.remove(tokenDigest) != null

    override fun deleteByUserId(userId: UserId): Int {
      val before = sessions.size
      sessions.entries.removeAll { it.value.userId == userId }
      return before - sessions.size
    }

    override fun deleteExpired(nowMillis: Long): Int {
      val before = sessions.size
      sessions.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
      return before - sessions.size
    }
  }
}
