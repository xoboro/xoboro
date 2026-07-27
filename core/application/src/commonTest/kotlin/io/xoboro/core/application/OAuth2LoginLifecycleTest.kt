package io.xoboro.core.application

import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.coroutines.startCoroutine

class OAuth2LoginLifecycleTest {
  @Test
  fun `lists providers starts OIDC and consumes a one-time state`() {
    runSuspend {
      val fixture = Fixture(accountCreationEnabled = true)
      fixture.gateway.identity =
        OAuth2ExternalIdentity(
          email = "reader@example.invalid",
          emailVerified = true,
        )

      assertEquals(
        listOf(OAuth2Provider("Synthetic OIDC", "synthetic")),
        fixture.lifecycle.providers(),
      )
      val launch =
        fixture.lifecycle.begin(
          registrationId = "synthetic",
          redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
        )
      assertEquals("https://identity.example.invalid/authorize?state=state-1", launch.redirectUri)
      assertEquals("nonce-1", fixture.gateway.authorizationNonce)

      val user =
        fixture.lifecycle.complete(
          "synthetic",
          launch.state,
          "authorization-code",
          launch.browserBinding,
        )
      assertEquals("reader@example.invalid", user.email)
      assertEquals("authorization-code", fixture.gateway.authorizationCode)
      assertEquals("nonce-1", fixture.gateway.expectedNonce)
      assertFailsWith<OAuth2LoginException> {
        runSuspend {
          fixture.lifecycle.complete(
            "synthetic",
            launch.state,
            "authorization-code",
            launch.browserBinding,
          )
        }
      }.also { assertEquals(OAuth2LoginLifecycle.INVALID_AUTHORIZATION_STATE, it.errorCode) }
    }
  }

  @Test
  fun `uses an existing account without creating a password`() {
    runSuspend {
      val fixture = Fixture(accountCreationEnabled = false)
      val existing = fixture.users.insertExisting("reader@example.invalid")
      fixture.gateway.identity =
        OAuth2ExternalIdentity(
          email = "READER@example.invalid",
          emailVerified = true,
        )
      val launch = fixture.lifecycle.begin("synthetic", CALLBACK_URI)

      assertSame(
        existing,
        fixture.lifecycle.complete("synthetic", launch.state, "code", launch.browserBinding),
      )
      assertEquals(0, fixture.passwordFactoryCalls)
    }
  }

  @Test
  fun `matches Komga OAuth2 and OIDC account errors`() {
    val cases =
      listOf(
        ErrorCase(
          protocol = OAuth2Protocol.OAUTH2,
          identity = OAuth2ExternalIdentity(null, null),
          accountCreationEnabled = true,
          expected = OAuth2LoginLifecycle.OAUTH2_EMAIL_MISSING,
        ),
        ErrorCase(
          protocol = OAuth2Protocol.OIDC,
          identity = OAuth2ExternalIdentity(null, true),
          accountCreationEnabled = true,
          expected = OAuth2LoginLifecycle.OIDC_EMAIL_MISSING,
        ),
        ErrorCase(
          protocol = OAuth2Protocol.OIDC,
          identity = OAuth2ExternalIdentity("reader@example.invalid", null),
          accountCreationEnabled = true,
          expected = OAuth2LoginLifecycle.OIDC_EMAIL_VERIFICATION_MISSING,
        ),
        ErrorCase(
          protocol = OAuth2Protocol.OIDC,
          identity = OAuth2ExternalIdentity("reader@example.invalid", false),
          accountCreationEnabled = true,
          expected = OAuth2LoginLifecycle.OIDC_EMAIL_NOT_VERIFIED,
        ),
        ErrorCase(
          protocol = OAuth2Protocol.OIDC,
          identity = OAuth2ExternalIdentity("reader@example.invalid", true),
          accountCreationEnabled = false,
          expected = OAuth2LoginLifecycle.ACCOUNT_CREATION_DISABLED,
        ),
      )

    cases.forEach { case ->
      val fixture =
        Fixture(
          protocol = case.protocol,
          accountCreationEnabled = case.accountCreationEnabled,
        )
      fixture.gateway.identity = case.identity
      val launch = fixture.lifecycle.begin("synthetic", CALLBACK_URI)
      val failure =
        assertFailsWith<OAuth2LoginException> {
          runSuspend {
            fixture.lifecycle.complete("synthetic", launch.state, "code", launch.browserBinding)
          }
        }
      assertEquals(case.expected, failure.errorCode)
    }
  }

  @Test
  fun `rejects expired mismatched and colliding authorization state`() {
    val fixture = Fixture(accountCreationEnabled = true)
    val first = fixture.lifecycle.begin("synthetic", CALLBACK_URI)
    fixture.clock = 601_001
    assertFailsWith<OAuth2LoginException> {
      runSuspend {
        fixture.lifecycle.complete("synthetic", first.state, "code", first.browserBinding)
      }
    }.also { assertEquals(OAuth2LoginLifecycle.INVALID_AUTHORIZATION_STATE, it.errorCode) }

    assertFailsWith<OAuth2LoginException> {
      fixture.lifecycle.begin("missing", CALLBACK_URI)
    }.also { assertEquals(OAuth2LoginLifecycle.UNKNOWN_REGISTRATION, it.errorCode) }

    val bindingFixture = Fixture(accountCreationEnabled = true)
    val bindingLaunch = bindingFixture.lifecycle.begin("synthetic", CALLBACK_URI)
    assertFailsWith<OAuth2LoginException> {
      runSuspend {
        bindingFixture.lifecycle.complete(
          "synthetic",
          bindingLaunch.state,
          "code",
          "attacker-browser-binding",
        )
      }
    }.also { assertEquals(OAuth2LoginLifecycle.INVALID_AUTHORIZATION_STATE, it.errorCode) }
  }

  private data class ErrorCase(
    val protocol: OAuth2Protocol,
    val identity: OAuth2ExternalIdentity,
    val accountCreationEnabled: Boolean,
    val expected: String,
  )

  private class Fixture(
    protocol: OAuth2Protocol = OAuth2Protocol.OIDC,
    accountCreationEnabled: Boolean,
  ) {
    val users = MutableUserRepository()
    val gateway = FakeOAuth2IdentityGateway()
    var clock = 1_000L
    var passwordFactoryCalls = 0
    private var stateSequence = 0
    private var nonceSequence = 0
    val lifecycle =
      OAuth2LoginLifecycle(
        registrations = listOf(registration(protocol)),
        users =
          UserLifecycle(
            users = users,
            passwordHasher =
              object : PasswordHasher {
                override fun hash(rawPassword: String): String = "hashed:$rawPassword"

                override fun matches(
                  rawPassword: String,
                  passwordHash: String,
                ): Boolean = passwordHash == "hashed:$rawPassword"
              },
            userIdFactory = { "user-${users.count() + 1}" },
            currentTimeMillis = { clock },
          ),
        pendingAuthorizations = InMemoryPendingStore(),
        identityGateway = gateway,
        accountCreationEnabled = accountCreationEnabled,
        oidcEmailVerificationEnabled = true,
        randomPasswordFactory = {
          passwordFactoryCalls += 1
          "synthetic-random-password"
        },
        stateFactory = {
          stateSequence += 1
          "state-$stateSequence"
        },
        browserBindingFactory = { "synthetic-browser-binding" },
        nonceFactory = {
          nonceSequence += 1
          "nonce-$nonceSequence"
        },
        currentTimeMillis = { clock },
      )
  }

  private class FakeOAuth2IdentityGateway : OAuth2IdentityGateway {
    var identity = OAuth2ExternalIdentity(null, null)
    var authorizationNonce: String? = null
    var authorizationCode: String? = null
    var expectedNonce: String? = null

    override fun authorizationUrl(
      registration: OAuth2ClientRegistration,
      redirectUri: String,
      state: String,
      nonce: String?,
    ): String {
      authorizationNonce = nonce
      return "${registration.authorizationUri}?state=$state"
    }

    override suspend fun exchange(
      registration: OAuth2ClientRegistration,
      redirectUri: String,
      authorizationCode: String,
      expectedNonce: String?,
    ): OAuth2ExternalIdentity {
      this.authorizationCode = authorizationCode
      this.expectedNonce = expectedNonce
      return identity
    }
  }

  private class InMemoryPendingStore : OAuth2PendingAuthorizationStore {
    private val values = mutableMapOf<String, OAuth2PendingAuthorization>()

    override fun save(pending: OAuth2PendingAuthorization): Boolean {
      if (pending.state in values) return false
      values[pending.state] = pending
      return true
    }

    override fun consume(state: String): OAuth2PendingAuthorization? = values.remove(state)

    override fun deleteExpired(cutoffMillis: Long): Int {
      val expired = values.values.filter { it.expiresAtMillis <= cutoffMillis }
      expired.forEach { values.remove(it.state) }
      return expired.size
    }
  }

  private class MutableUserRepository : UserRepository {
    private val values = linkedMapOf<UserId, User>()

    override fun count(): Long = values.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = values[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      values.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = values.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      values[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (values.isNotEmpty()) return false
      insert(user)
      return true
    }

    override fun update(user: User) {
      values[user.id] = user
    }

    override fun delete(id: UserId) {
      values.remove(id)
    }

    fun insertExisting(email: String): User =
      User(
        id = UserId("existing-user"),
        email = email,
        passwordHash = "synthetic-hash",
        createdAtMillis = 1,
      ).also(::insert)
  }

  private companion object {
    const val CALLBACK_URI = "https://reader.example.invalid/login/oauth2/code/synthetic"

    fun registration(protocol: OAuth2Protocol): OAuth2ClientRegistration =
      OAuth2ClientRegistration(
        registrationId = "synthetic",
        clientName = "Synthetic OIDC",
        clientId = "synthetic-client",
        clientSecret = "synthetic-secret",
        authorizationUri = "https://identity.example.invalid/authorize",
        tokenUri = "https://identity.example.invalid/token",
        userInfoUri = "https://identity.example.invalid/userinfo",
        scopes = listOf("openid", "email"),
        protocol = protocol,
        issuerUri =
          if (protocol == OAuth2Protocol.OIDC) "https://identity.example.invalid" else null,
        jwkSetUri =
          if (protocol == OAuth2Protocol.OIDC) {
            "https://identity.example.invalid/.well-known/jwks.json"
          } else {
            null
          },
      )
  }
}

private fun <T> runSuspend(block: suspend () -> T): T {
  var completion: Result<T>? = null
  block.startCoroutine(
    object : kotlin.coroutines.Continuation<T> {
      override val context = kotlin.coroutines.EmptyCoroutineContext

      override fun resumeWith(result: Result<T>) {
        completion = result
      }
    },
  )
  return requireNotNull(completion).getOrThrow()
}
