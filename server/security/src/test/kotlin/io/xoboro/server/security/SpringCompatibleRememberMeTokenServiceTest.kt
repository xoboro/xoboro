package io.xoboro.server.security

import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpringCompatibleRememberMeTokenServiceTest {
  @Test
  fun `issues validates expires and invalidates Spring compatible tokens`() {
    var user = syntheticUser()
    val users = MutableSingleUserRepository { user }
    var now = 1_000L
    val service =
      SpringCompatibleRememberMeTokenService(
        users = users,
        secretKey = "synthetic-server-secret",
        currentTimeMillis = { now },
        tokenValidityMillis = 5_000,
      )

    val token = service.issue(user)
    val decoded =
      String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8)
        .split(':')
    assertEquals(listOf(user.email, "6000", "SHA256"), decoded.take(3))
    assertEquals(expectedSignature(user, 6_000), decoded[3])
    assertEquals(user, service.authenticate(token))

    user = user.copy(passwordHash = "updated-synthetic-hash")
    assertNull(service.authenticate(token))

    user = syntheticUser()
    now = 6_001
    assertNull(service.authenticate(token))
    assertNull(service.authenticate("malformed-token"))
  }

  @Test
  fun `uses the current secret and duration without restarting`() {
    val user = syntheticUser()
    val users = MutableSingleUserRepository { user }
    var secret = "synthetic-key-1"
    var validityMillis = 5_000L
    val service =
      SpringCompatibleRememberMeTokenService(
        users = users,
        secretKeyProvider = { secret },
        currentTimeMillis = { 1_000 },
        tokenValidityMillisProvider = { validityMillis },
      )

    val firstToken = service.issue(user)
    assertEquals(5, service.maxAgeSeconds())
    assertEquals(user, service.authenticate(firstToken))

    secret = "synthetic-key-2"
    assertNull(service.authenticate(firstToken))

    validityMillis = 8_000
    assertEquals(8, service.maxAgeSeconds())
    val decoded =
      String(Base64.getDecoder().decode(service.issue(user)), StandardCharsets.UTF_8)
        .split(':')
    assertEquals("9000", decoded[1])
  }

  private fun expectedSignature(
    user: User,
    expiry: Long,
  ): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(
        "${user.email}:$expiry:${user.passwordHash}:synthetic-server-secret"
          .toByteArray(StandardCharsets.UTF_8),
      ).joinToString("") { byte -> "%02x".format(byte) }

  private fun syntheticUser(): User =
    User(
      id = UserId("user-1"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-password-hash",
      createdAtMillis = 0,
    )

  private class MutableSingleUserRepository(
    private val current: () -> User,
  ) : UserRepository {
    override fun count(): Long = 1

    override fun findByIdOrNull(id: UserId): User? = current().takeIf { it.id == id }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      current().takeIf { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = listOf(current())

    override fun insert(user: User) = throw UserEmailAlreadyExistsException(user.email)

    override fun claimIfEmpty(user: User): Boolean = false

    override fun update(user: User) = Unit

    override fun delete(id: UserId) = Unit
  }
}
