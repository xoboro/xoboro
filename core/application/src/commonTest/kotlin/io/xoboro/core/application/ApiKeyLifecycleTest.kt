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

  private fun lifecycle(
    users: InMemoryUsers,
    apiKeys: InMemoryApiKeys,
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
      currentTimeMillis = { 100 },
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
