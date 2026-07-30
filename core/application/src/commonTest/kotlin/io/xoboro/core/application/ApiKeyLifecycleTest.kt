package io.xoboro.core.application

import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyCommentAlreadyExistsException
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.ApiKeyRepository
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApiKeyLifecycleTest {
  @Test
  fun `creates authenticates lists and deletes a trimmed API key`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val apiKeys = InMemoryApiKeys()
    val lifecycle = lifecycle(users, apiKeys)

    val created = requireNotNull(lifecycle.create(user.id, " Synthetic client "))

    assertEquals("plain-1", created.plainTextKey)
    assertEquals("encoded:plain-1", created.apiKey.keyHash)
    assertEquals("Synthetic client", created.apiKey.comment)
    assertEquals(user, lifecycle.authenticate("plain-1")?.user)
    assertNull(lifecycle.authenticate("wrong-token"))
    assertEquals(listOf(created.apiKey), lifecycle.findAll(user.id))
    assertEquals(true, lifecycle.delete(user.id, created.apiKey.id))
    assertEquals(false, lifecycle.delete(user.id, created.apiKey.id))
  }

  @Test
  fun `rejects duplicate comments without exposing case differences`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val apiKeys = InMemoryApiKeys()
    val lifecycle = lifecycle(users, apiKeys)
    lifecycle.create(user.id, "Synthetic client")

    assertFailsWith<ApiKeyCommentAlreadyExistsException> {
      lifecycle.create(user.id, " synthetic CLIENT ")
    }
  }

  @Test
  fun `retries hash collisions up to the Komga limit`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val apiKeys = InMemoryApiKeys(rejectEveryHash = true)
    val generated = mutableListOf<String>()
    val lifecycle =
      ApiKeyLifecycle(
        users = users,
        apiKeys = apiKeys,
        tokenEncoder = TokenEncoder { "encoded:$it" },
        apiKeyIdFactory = { "key-${generated.size}" },
        plainTextKeyFactory = {
          "plain-${generated.size + 1}".also(generated::add)
        },
        currentTimeMillis = { 100 },
      )

    assertNull(lifecycle.create(user.id, "Synthetic client"))
    assertEquals(ApiKeyLifecycle.MAX_GENERATION_ATTEMPTS, generated.size)
  }

  @Test
  fun `narrows the authenticated caller to the key's scopes`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val apiKeys = InMemoryApiKeys()
    val lifecycle = lifecycle(users, apiKeys)

    lifecycle.create(user.id, "Streaming only", scopes = setOf(UserRole.PAGE_STREAMING))

    // The owner holds FILE_DOWNLOAD as well; the key must not carry it. Every downstream role check
    // and every catalogAccess() projection reads this User, so narrowing here is what enforces the
    // scope everywhere.
    val principal = requireNotNull(lifecycle.authenticate("plain-1"))
    assertEquals(setOf(UserRole.PAGE_STREAMING), principal.user.roles)
    assertEquals(setOf(UserRole.PAGE_STREAMING), principal.apiKey.scopes)
  }

  @Test
  fun `an unscoped key authenticates with the owner's full capabilities`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val lifecycle = lifecycle(users, InMemoryApiKeys())

    lifecycle.create(user.id, "Everything")

    // Keys created before scoping existed read back with no scopes and must keep working unchanged.
    assertEquals(user.roles, requireNotNull(lifecycle.authenticate("plain-1")).user.roles)
  }

  @Test
  fun `rejects a scope the owner does not hold`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val lifecycle = lifecycle(users, InMemoryApiKeys())

    // At creation an unsatisfiable scope is a caller mistake worth reporting, unlike at
    // authentication where a reduced owner role set is expected and is silently narrowed.
    assertFailsWith<IllegalArgumentException> {
      lifecycle.create(user.id, "Escalation", scopes = setOf(UserRole.ADMIN))
    }
  }

  @Test
  fun `refuses to authenticate a key once its expiry passes`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    var now = 100L
    val lifecycle = lifecycle(users, InMemoryApiKeys(), currentTimeMillis = { now })

    lifecycle.create(user.id, "Expiring", expiresAtMillis = 200)

    now = 199
    assertEquals(user.id, lifecycle.authenticate("plain-1")?.user?.id)
    now = 200
    assertNull(lifecycle.authenticate("plain-1"))
    now = 10_000
    assertNull(lifecycle.authenticate("plain-1"))

    // Expiry is a read-time decision, not a sweep: the row is still listed so its owner can see the
    // key that stopped working and delete it deliberately.
    assertEquals(1, lifecycle.findAll(user.id).size)
  }

  @Test
  fun `rejects an expiry that has already passed`() {
    val users = InMemoryUsers()
    val user = users.addUser()
    val lifecycle = lifecycle(users, InMemoryApiKeys())

    assertFailsWith<IllegalArgumentException> {
      lifecycle.create(user.id, "Already dead", expiresAtMillis = 100)
    }
    assertFailsWith<IllegalArgumentException> {
      lifecycle.create(user.id, "Born dead", expiresAtMillis = 99)
    }
  }

  private fun lifecycle(
    users: InMemoryUsers,
    apiKeys: InMemoryApiKeys,
    currentTimeMillis: () -> Long = { 100 },
  ): ApiKeyLifecycle {
    var sequence = 0
    return ApiKeyLifecycle(
      users = users,
      apiKeys = apiKeys,
      tokenEncoder = TokenEncoder { "encoded:$it" },
      apiKeyIdFactory = { "key-${sequence + 1}" },
      plainTextKeyFactory = {
        sequence += 1
        "plain-$sequence"
      },
      currentTimeMillis = currentTimeMillis,
    )
  }

  private class InMemoryUsers : UserRepository {
    private val users = linkedMapOf<UserId, User>()

    fun addUser(): User =
      User(
        id = UserId("user-1"),
        email = "reader@example.invalid",
        passwordHash = "synthetic-hash",
        createdAtMillis = 1,
      ).also { insert(it) }

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

  private class InMemoryApiKeys(
    private val rejectEveryHash: Boolean = false,
  ) : ApiKeyRepository {
    private val keys = linkedMapOf<ApiKeyId, ApiKey>()

    override fun findByKeyHashOrNull(keyHash: String): ApiKey? =
      keys.values.firstOrNull { it.keyHash == keyHash }

    override fun findAllByUserId(userId: UserId): List<ApiKey> =
      keys.values.filter { it.userId == userId }

    override fun existsByCommentIgnoreCase(
      userId: UserId,
      comment: String,
    ): Boolean =
      keys.values.any {
        it.userId == userId && it.comment.equals(comment, ignoreCase = true)
      }

    override fun insert(apiKey: ApiKey) {
      if (rejectEveryHash || findByKeyHashOrNull(apiKey.keyHash) != null) {
        throw ApiKeyHashAlreadyExistsException()
      }
      keys[apiKey.id] = apiKey
    }

    override fun deleteByIdAndUserId(
      id: ApiKeyId,
      userId: UserId,
    ): Boolean =
      keys[id]
        ?.takeIf { it.userId == userId }
        ?.let {
          keys.remove(id)
          true
        } ?: false
  }
}
