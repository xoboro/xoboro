package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.OAuth2AccountLinking
import io.xoboro.core.application.OAuth2ClientRegistration
import io.xoboro.core.application.OAuth2ExternalIdentity
import io.xoboro.core.application.OAuth2IdentityGateway
import io.xoboro.core.application.OAuth2LoginLifecycle
import io.xoboro.core.application.OAuth2PendingAuthorization
import io.xoboro.core.application.OAuth2PendingAuthorizationStore
import io.xoboro.core.application.OAuth2Protocol
import io.xoboro.core.application.PasswordHasher
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class XoboroNativeOAuth2Test {
  @Test
  fun `reports resolved providers and the effective policy to an administrator`() =
    testApplication {
      val fixture = install()

      val response =
        client.get("$XOBORO_API_PREFIX/authentication/oauth2") {
          bearerAuth(fixture.administratorToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroOAuth2ConfigurationResponse>()
      assertEquals(
        listOf(
          XoboroOAuth2ProviderResponse("alpha", "Alpha provider"),
          XoboroOAuth2ProviderResponse("beta", "Beta provider"),
        ),
        body.providers,
      )
      assertEquals(true, body.accountCreationEnabled)
      assertEquals(false, body.oidcEmailVerificationRequired)
      assertEquals("NEVER", body.accountLinking)
    }

  @Test
  fun `never returns a client id secret or endpoint`() =
    testApplication {
      val fixture = install()

      val raw =
        client
          .get("$XOBORO_API_PREFIX/authentication/oauth2") {
            bearerAuth(fixture.administratorToken)
          }.body<String>()

      // Asserted against the raw body rather than the DTO: a field added to the response later would
      // pass a DTO-shaped assertion while leaking here.
      listOf(
        SECRET,
        "synthetic-client-id",
        "https://provider.example.invalid",
      ).forEach { forbidden ->
        assertFalse(forbidden in raw, "response disclosed $forbidden")
      }
    }

  @Test
  fun `refuses a non-administrator`() =
    testApplication {
      val fixture = install()

      val response =
        client.get("$XOBORO_API_PREFIX/authentication/oauth2") {
          bearerAuth(fixture.readerToken)
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("forbidden", response.body<XoboroApiError>().code)
    }

  @Test
  fun `refuses an unauthenticated caller`() =
    testApplication {
      install()

      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("$XOBORO_API_PREFIX/authentication/oauth2").status,
      )
    }

  private fun ApplicationTestBuilder.install(): Fixture {
    val fixture = Fixture()
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      routing {
        xoboroNativeOAuth2Routes(fixture.oauth2)
      }
    }
    createClient {
      install(ClientContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
      }
    }.also { client = it }
    return fixture
  }

  private class Fixture {
    private val repository = InMemoryUserRepository()
    private var tokenSequence = 0
    val sessions =
      UserSessionLifecycle(
        users = repository,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = Sha512TokenEncoder(),
        plainTokenFactory = { "session-${++tokenSequence}" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    private val users =
      UserLifecycle(
        users = repository,
        passwordHasher =
          object : PasswordHasher {
            override fun hash(rawPassword: String): String = "hash:$rawPassword"

            override fun matches(
              rawPassword: String,
              passwordHash: String,
            ): Boolean = passwordHash == hash(rawPassword)
          },
        userIdFactory = { "user-${repository.count() + 1}" },
        currentTimeMillis = { 1_000 },
      )
    val oauth2 =
      OAuth2LoginLifecycle(
        registrations =
          listOf(
            // Declared out of order so the route's sort is what produces the assertion's order.
            registration("beta", "Beta provider"),
            registration("alpha", "Alpha provider"),
          ),
        users = users,
        pendingAuthorizations = NoPendingAuthorizations,
        identityGateway = UnusedGateway,
        accountCreationEnabled = true,
        oidcEmailVerificationEnabled = false,
        accountLinking = OAuth2AccountLinking.NEVER,
        randomPasswordFactory = { "synthetic-random-password" },
        stateFactory = { "synthetic-state" },
        browserBindingFactory = { "synthetic-binding" },
        nonceFactory = { "synthetic-nonce" },
        currentTimeMillis = { 1_000 },
      )
    val administratorToken: String =
      users
        .claimInitialAdministrator("admin@example.invalid", "synthetic-password")
        .let { requireNotNull(sessions.create(it)).plainToken }
    val readerToken: String =
      users
        .createUser(
          email = "reader@example.invalid",
          rawPassword = "synthetic-password",
          roles = setOf(UserRole.PAGE_STREAMING),
        ).let { requireNotNull(sessions.create(it)).plainToken }

    private fun registration(
      id: String,
      name: String,
    ): OAuth2ClientRegistration =
      OAuth2ClientRegistration(
        registrationId = id,
        clientName = name,
        clientId = "synthetic-client-id",
        clientSecret = SECRET,
        scopes = listOf("openid", "email"),
        protocol = OAuth2Protocol.OIDC,
        issuerUri = "https://provider.example.invalid",
      )
  }

  private object NoPendingAuthorizations : OAuth2PendingAuthorizationStore {
    override fun save(pending: OAuth2PendingAuthorization): Boolean = true

    override fun consume(state: String): OAuth2PendingAuthorization? = null

    override fun deleteExpired(cutoffMillis: Long): Int = 0
  }

  private object UnusedGateway : OAuth2IdentityGateway {
    override suspend fun authorizationUrl(
      registration: OAuth2ClientRegistration,
      redirectUri: String,
      state: String,
      nonce: String?,
    ): String = error("The configuration route must not start an authorization")

    override suspend fun exchange(
      registration: OAuth2ClientRegistration,
      redirectUri: String,
      authorizationCode: String,
      expectedNonce: String?,
    ): OAuth2ExternalIdentity =
      error("The configuration route must not exchange a code")
  }

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

  private companion object {
    // Synthetic, and asserted absent from every response.
    const val SECRET = "synthetic-client-secret"
  }
}
