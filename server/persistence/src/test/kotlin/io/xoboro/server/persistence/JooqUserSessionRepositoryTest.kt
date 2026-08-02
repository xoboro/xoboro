package io.xoboro.server.persistence

import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.SessionTouch
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserSession
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqUserSessionRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `authenticates a digest-only session after database restart`() {
    val path = tempDirectory.resolve("restart.sqlite")
    var now = 100L

    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val users = JooqUserRepository(database)
      users.insert(user())
      val created =
        lifecycle(database, now = { now }, plainTokenFactory = { PLAIN_TOKEN })
          .create(user())

      assertEquals(PLAIN_TOKEN, created.plainToken)
      assertEquals(
        TOKEN_DIGEST,
        database.dsl.fetchValue("SELECT token_digest FROM user_session", String::class.java),
      )
      assertEquals(
        0,
        database.dsl
          .fetchOne(
            "SELECT count(*) FROM user_session WHERE token_digest = ?",
            PLAIN_TOKEN,
          )?.get(0, Int::class.java),
      )
    }

    now = 150
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      assertEquals(user(), lifecycle(database, now = { now }).authenticate(PLAIN_TOKEN))
      val restored = JooqUserSessionRepository(database).findByTokenDigestOrNull(TOKEN_DIGEST)
      assertEquals(150, restored?.lastAccessedAtMillis)
      assertEquals(650, restored?.expiresAtMillis)
    }
  }

  @Test
  fun `inserts uniquely and extends active expiry monotonically`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("touch.sqlite"))).use { database ->
      JooqUserRepository(database).insert(user())
      val repository = JooqUserSessionRepository(database)
      val session = session(expiresAtMillis = 200)

      assertTrue(repository.insertIfAbsent(session))
      assertFalse(repository.insertIfAbsent(session))
      assertEquals(SessionTouch.TOUCHED, repository.touchIfActive(TOKEN_DIGEST, 150, 650))
      assertEquals(SessionTouch.TOUCHED, repository.touchIfActive(TOKEN_DIGEST, 125, 625))
      assertEquals(
        session.copy(lastAccessedAtMillis = 150, expiresAtMillis = 650),
        repository.findByTokenDigestOrNull(TOKEN_DIGEST),
      )
      assertEquals(SessionTouch.EXPIRED, repository.touchIfActive(TOKEN_DIGEST, 650, 1_150))
      assertEquals(1, repository.deleteExpired(650))
      assertNull(repository.findByTokenDigestOrNull(TOKEN_DIGEST))
      assertFalse(repository.deleteByTokenDigest(TOKEN_DIGEST))
    }
  }

  @Test
  fun `revokes by owner and cascades remaining sessions with user deletion`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("revocation.sqlite"))).use { database ->
      val users = JooqUserRepository(database)
      users.insert(user())
      val repository = JooqUserSessionRepository(database)
      repository.insertIfAbsent(session())
      repository.insertIfAbsent(session().copy(tokenDigest = "digest:second"))

      assertEquals(2, repository.deleteByUserId(USER_ID))
      repository.insertIfAbsent(session())
      users.delete(USER_ID)

      assertNull(repository.findByTokenDigestOrNull(TOKEN_DIGEST))
    }
  }

  @Test
  fun `reports a locked database as unavailable rather than as an expiry`() {
    // Found against a real library: a scan of 18,211 archives held the write lock, the
    // session touch every authenticated request performs waited out its busy timeout, and
    // the exception failed a plain GET with a 500. Reporting it as an expiry instead would
    // be worse still - a busy moment would log the operator out.
    //
    // The lock is taken for real from a second connection rather than simulated, because
    // what is being checked is the driver's result code surviving jOOQ's wrapping.
    val path = tempDirectory.resolve("locked.sqlite")
    XoboroDatabase.open(DatabaseConfig(path, busyTimeoutMillis = 50)).use { database ->
      JooqUserRepository(database).insert(user())
      val sessions = JooqUserSessionRepository(database)
      assertTrue(sessions.insertIfAbsent(session()))

      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { holder ->
        holder.autoCommit = false
        // A write, not `BEGIN IMMEDIATE`: turning autocommit off already opened the
        // transaction, and the write is what actually takes and holds the WAL write lock.
        holder.createStatement().use {
          it.executeUpdate("UPDATE user_session SET last_accessed_at_ms = last_accessed_at_ms")
        }
        try {
          assertEquals(
            SessionTouch.UNAVAILABLE,
            sessions.touchIfActive(TOKEN_DIGEST, accessedAtMillis = 100, expiresAtMillis = 600),
          )
        } finally {
          holder.rollback()
        }
      }

      // Once the lock is gone the same call succeeds, so the session was never revoked.
      assertEquals(
        SessionTouch.TOUCHED,
        sessions.touchIfActive(TOKEN_DIGEST, accessedAtMillis = 100, expiresAtMillis = 600),
      )
    }
  }

  private fun lifecycle(
    database: XoboroDatabase,
    now: () -> Long,
    plainTokenFactory: () -> String = { error("Session creation was not expected") },
  ): UserSessionLifecycle =
    UserSessionLifecycle(
      users = JooqUserRepository(database),
      sessions = JooqUserSessionRepository(database),
      tokenEncoder = TokenEncoder { "digest:$it" },
      plainTokenFactory = plainTokenFactory,
      currentTimeMillis = now,
      inactivityTimeoutMillis = 500,
    )

  private fun user(): User =
    User(
      id = USER_ID,
      email = "reader@example.invalid",
      passwordHash = "synthetic-password-hash",
      createdAtMillis = 1,
    )

  private fun session(expiresAtMillis: Long = 500): UserSession =
    UserSession(
      tokenDigest = TOKEN_DIGEST,
      userId = USER_ID,
      createdAtMillis = 100,
      expiresAtMillis = expiresAtMillis,
    )

  private companion object {
    const val PLAIN_TOKEN = "plain-session-token"
    const val TOKEN_DIGEST = "digest:plain-session-token"
    val USER_ID = UserId("user-1")
  }
}
