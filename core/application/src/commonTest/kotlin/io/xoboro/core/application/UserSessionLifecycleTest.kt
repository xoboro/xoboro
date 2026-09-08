package io.xoboro.core.application

import io.xoboro.core.domain.SessionInsert
import io.xoboro.core.domain.SessionTouch
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserSession
import io.xoboro.core.domain.UserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserSessionLifecycleTest {
  @Test
  fun `does not touch a recently accessed active session`() {
    val user = syntheticUser()
    val sessions = InMemorySessionRepository()
    sessions.insertIfAbsent(
      session(
        "hash:token",
        user.id,
        lastAccessedAtMillis = 90,
        expiresAtMillis = 1_000,
      ),
    )
    val lifecycle =
      UserSessionLifecycle(
        users = SingleUserRepository(user),
        sessions = sessions,
        tokenEncoder = TokenEncoder { "hash:$it" },
        plainTokenFactory = { "unused" },
        currentTimeMillis = { 100 },
        inactivityTimeoutMillis = 500,
        sessionTouchIntervalMillis = 60,
      )

    assertEquals(user, lifecycle.authenticate("token"))
    assertEquals(0, sessions.touchCallCount)
    assertEquals(1_000, sessions.findByTokenDigestOrNull("hash:token")?.expiresAtMillis)
  }

  @Test
  fun `touches an active session at the refresh interval boundary`() {
    val user = syntheticUser()
    val sessions = InMemorySessionRepository()
    sessions.insertIfAbsent(
      session(
        "hash:token",
        user.id,
        lastAccessedAtMillis = 40,
        expiresAtMillis = 1_000,
      ),
    )
    val lifecycle =
      UserSessionLifecycle(
        users = SingleUserRepository(user),
        sessions = sessions,
        tokenEncoder = TokenEncoder { "hash:$it" },
        plainTokenFactory = { "unused" },
        currentTimeMillis = { 100 },
        inactivityTimeoutMillis = 500,
        sessionTouchIntervalMillis = 60,
      )

    assertEquals(user, lifecycle.authenticate("token"))
    assertEquals(1, sessions.touchCallCount)
    assertEquals(100, sessions.findByTokenDigestOrNull("hash:token")?.lastAccessedAtMillis)
  }

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

    val created = assertNotNull(lifecycle.create(user))
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
    assertEquals(0, lifecycle.deleteExpired())
    assertEquals(false, lifecycle.invalidate("missing"))
  }

  @Test
  fun `does not revoke a session extended after a stale read`() {
    val user = syntheticUser()
    val sessions = InMemorySessionRepository()
    sessions.insertIfAbsent(session("hash:token", user.id, expiresAtMillis = 100))
    sessions.afterNextFind = {
      sessions.replace(session("hash:token", user.id, expiresAtMillis = 500))
    }
    val lifecycle =
      UserSessionLifecycle(
        users = SingleUserRepository(user),
        sessions = sessions,
        tokenEncoder = TokenEncoder { "hash:$it" },
        plainTokenFactory = { "unused" },
        currentTimeMillis = { 100 },
        inactivityTimeoutMillis = 500,
        sessionTouchIntervalMillis = 60,
      )

    assertEquals(user, lifecycle.authenticate("token"))
    assertEquals(1, sessions.touchCallCount)
    assertEquals(600, sessions.findByTokenDigestOrNull("hash:token")?.expiresAtMillis)
  }

  @Test
  fun `keeps authenticating when the store cannot record the access`() {
    // Found against a real library: while a scan of 18,211 archives held the SQLite write
    // lock, `touchIfActive` exceeded its busy timeout and the exception failed the whole
    // request with a 500 — for a plain read, which only became a write because every
    // authenticated call extends the session's sliding window.
    //
    // Losing one extension is harmless: the window is 500ms shy of what it would have been
    // and the next request extends it. Refusing the request, or worse treating the failure
    // as an expiry and logging the operator out, is not. So the store reports that it could
    // not record the access, and that is distinct from reporting the session expired.
    val user = syntheticUser()
    val sessions = InMemorySessionRepository()
    sessions.insertIfAbsent(session("hash:token", user.id, expiresAtMillis = 1_000))
    sessions.touchUnavailable = true
    val lifecycle =
      UserSessionLifecycle(
        users = SingleUserRepository(user),
        sessions = sessions,
        tokenEncoder = TokenEncoder { "hash:$it" },
        plainTokenFactory = { "unused" },
        currentTimeMillis = { 100 },
        inactivityTimeoutMillis = 500,
        sessionTouchIntervalMillis = 60,
      )

    assertEquals(user, lifecycle.authenticate("token"))
    assertEquals(1, sessions.touchCallCount)
    // The session survives: an unavailable store must not read as a revocation.
    assertEquals(1_000, sessions.findByTokenDigestOrNull("hash:token")?.expiresAtMillis)
  }

  @Test
  fun `reports no session rather than an exhausted-token failure when the store is busy`() {
    // The sibling of `keeps authenticating when the store cannot record the access`, found the same
    // way: a scan of 18,211 archives held the write lock while a `Basic` request opened its
    // session, and the INSERT - unlike the UPDATE that fix covered - still threw.
    //
    // What makes this its own case is that `insertIfAbsent` already had a false: "that digest is
    // taken, generate another token". Folding contention into it would spend all three generation
    // attempts on a store that was never going to accept any token, and then report
    // `Failed to generate a unique session token` - a token-factory bug - for a busy moment.
    val user = syntheticUser()
    val sessions = InMemorySessionRepository()
    sessions.insertUnavailable = true
    var tokensGenerated = 0
    val lifecycle =
      UserSessionLifecycle(
        users = SingleUserRepository(user),
        sessions = sessions,
        tokenEncoder = TokenEncoder { "hash:$it" },
        plainTokenFactory = { "token-${++tokensGenerated}" },
        currentTimeMillis = { 100 },
        inactivityTimeoutMillis = 500,
      )

    assertNull(lifecycle.create(user))
    // One attempt, not three: no token is going to be accepted, so the remaining attempts would
    // only delay the answer the caller has to act on.
    assertEquals(1, tokensGenerated)
    assertNull(sessions.findByTokenDigestOrNull("hash:token-1"))
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
    lastAccessedAtMillis: Long = 0,
    expiresAtMillis: Long,
  ): UserSession =
    UserSession(
      tokenDigest = digest,
      userId = userId,
      createdAtMillis = 0,
      lastAccessedAtMillis = lastAccessedAtMillis,
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
    var afterNextFind: (() -> Unit)? = null

    override fun findByTokenDigestOrNull(tokenDigest: String): UserSession? {
      val found = sessions[tokenDigest]
      afterNextFind?.also { afterNextFind = null }?.invoke()
      return found
    }

    fun replace(session: UserSession) {
      sessions[session.tokenDigest] = session
    }

    /** Makes the store refuse every insert, as a busy database does. */
    var insertUnavailable = false

    override fun insertIfAbsent(session: UserSession): SessionInsert {
      if (insertUnavailable) return SessionInsert.UNAVAILABLE
      if (session.tokenDigest in sessions) return SessionInsert.DIGEST_TAKEN
      sessions[session.tokenDigest] = session
      return SessionInsert.INSERTED
    }

    /** Makes the store report that it could not record the access, as a busy database does. */
    var touchUnavailable = false
    var touchCallCount = 0

    override fun touchIfActive(
      tokenDigest: String,
      accessedAtMillis: Long,
      expiresAtMillis: Long,
    ): SessionTouch {
      touchCallCount += 1
      if (touchUnavailable) return SessionTouch.UNAVAILABLE
      val current = sessions[tokenDigest] ?: return SessionTouch.EXPIRED
      if (current.expiresAtMillis <= accessedAtMillis) return SessionTouch.EXPIRED
      sessions[tokenDigest] =
        current.copy(
          lastAccessedAtMillis = maxOf(current.lastAccessedAtMillis, accessedAtMillis),
          expiresAtMillis = maxOf(current.expiresAtMillis, expiresAtMillis),
        )
      return SessionTouch.TOUCHED
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
