package io.xoboro.core.application

import io.xoboro.core.domain.User

enum class OAuth2Protocol {
  OAUTH2,
  OIDC,
}

enum class OAuth2ClientAuthenticationMethod {
  CLIENT_SECRET_BASIC,
  CLIENT_SECRET_POST,
}

data class OAuth2ClientRegistration(
  val registrationId: String,
  val clientName: String,
  val clientId: String,
  val clientSecret: String,
  val authorizationUri: String? = null,
  val tokenUri: String? = null,
  val userInfoUri: String? = null,
  val scopes: List<String>,
  val protocol: OAuth2Protocol = OAuth2Protocol.OAUTH2,
  val issuerUri: String? = null,
  val jwkSetUri: String? = null,
  val clientAuthenticationMethod: OAuth2ClientAuthenticationMethod =
    OAuth2ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
) {
  init {
    require(REGISTRATION_ID.matches(registrationId)) { "OAuth2 registration ID is invalid" }
    require(clientName.isNotBlank()) { "OAuth2 client name must not be blank" }
    require(clientId.isNotBlank()) { "OAuth2 client ID must not be blank" }
    require(clientSecret.isNotBlank()) { "OAuth2 client secret must not be blank" }
    require(scopes.none(String::isBlank)) { "OAuth2 scopes must not be blank" }
    if (protocol == OAuth2Protocol.OIDC) {
      require(issuerUri?.isWebUri() == true) { "OIDC issuer URI must use HTTP or HTTPS" }
      require(authorizationUri == null || authorizationUri.isWebUri()) {
        "OIDC authorization URI must use HTTP or HTTPS"
      }
      require(tokenUri == null || tokenUri.isWebUri()) {
        "OIDC token URI must use HTTP or HTTPS"
      }
      require(userInfoUri == null || userInfoUri.isWebUri()) {
        "OIDC user-info URI must use HTTP or HTTPS"
      }
      require(jwkSetUri == null || jwkSetUri.isWebUri()) {
        "OIDC JWK-set URI must use HTTP or HTTPS"
      }
      require("openid" in scopes) { "OIDC scopes must include openid" }
    } else {
      require(authorizationUri?.isWebUri() == true) {
        "OAuth2 authorization URI must use HTTP or HTTPS"
      }
      require(tokenUri?.isWebUri() == true) {
        "OAuth2 token URI must use HTTP or HTTPS"
      }
      require(userInfoUri?.isWebUri() == true) {
        "OAuth2 user-info URI must use HTTP or HTTPS"
      }
    }
  }

  override fun toString(): String =
    "OAuth2ClientRegistration(" +
      "registrationId=$registrationId, " +
      "clientName=$clientName, " +
      "clientId=$clientId, " +
      "clientSecret=[REDACTED], " +
      "authorizationUri=$authorizationUri, " +
      "tokenUri=$tokenUri, " +
      "userInfoUri=$userInfoUri, " +
      "scopes=$scopes, " +
      "protocol=$protocol, " +
      "issuerUri=$issuerUri, " +
      "jwkSetUri=$jwkSetUri, " +
      "clientAuthenticationMethod=$clientAuthenticationMethod)"

  private companion object {
    val REGISTRATION_ID = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
  }
}

data class OAuth2Provider(
  val name: String,
  val registrationId: String,
)

data class OAuth2PendingAuthorization(
  val state: String,
  val registrationId: String,
  val redirectUri: String,
  val browserBinding: String,
  val nonce: String?,
  val expiresAtMillis: Long,
)

interface OAuth2PendingAuthorizationStore {
  fun save(pending: OAuth2PendingAuthorization): Boolean

  fun consume(state: String): OAuth2PendingAuthorization?

  fun deleteExpired(cutoffMillis: Long): Int
}

data class OAuth2ExternalIdentity(
  val email: String?,
  val emailVerified: Boolean?,
)

interface OAuth2IdentityGateway {
  suspend fun authorizationUrl(
    registration: OAuth2ClientRegistration,
    redirectUri: String,
    state: String,
    nonce: String?,
  ): String

  suspend fun exchange(
    registration: OAuth2ClientRegistration,
    redirectUri: String,
    authorizationCode: String,
    expectedNonce: String?,
  ): OAuth2ExternalIdentity
}

data class OAuth2AuthorizationLaunch(
  val redirectUri: String,
  val state: String,
  val browserBinding: String,
)

class OAuth2LoginException(
  val errorCode: String,
) : IllegalArgumentException(errorCode)

class OAuth2LoginLifecycle(
  registrations: List<OAuth2ClientRegistration>,
  private val users: UserLifecycle,
  private val pendingAuthorizations: OAuth2PendingAuthorizationStore,
  private val identityGateway: OAuth2IdentityGateway,
  private val accountCreationEnabled: Boolean,
  private val oidcEmailVerificationEnabled: Boolean,
  private val randomPasswordFactory: () -> String,
  private val stateFactory: () -> String,
  private val browserBindingFactory: () -> String,
  private val nonceFactory: () -> String,
  private val currentTimeMillis: () -> Long,
  private val authorizationTtlMillis: Long = 10 * 60 * 1_000,
) {
  private val registrations = registrations.associateBy(OAuth2ClientRegistration::registrationId)

  init {
    require(this.registrations.size == registrations.size) {
      "OAuth2 registration IDs must be unique"
    }
    require(authorizationTtlMillis > 0) { "OAuth2 authorization TTL must be positive" }
  }

  fun providers(): List<OAuth2Provider> =
    registrations.values.map { OAuth2Provider(it.clientName, it.registrationId) }

  suspend fun begin(
    registrationId: String,
    redirectUri: String,
  ): OAuth2AuthorizationLaunch {
    val registration = registration(registrationId)
    require(redirectUri.isWebUri()) { "OAuth2 redirect URI must use HTTP or HTTPS" }
    val now = now()
    pendingAuthorizations.deleteExpired(now)
    repeat(MAX_TOKEN_GENERATION_ATTEMPTS) {
      val state = stateFactory().also(::requireToken)
      val browserBinding = browserBindingFactory().also(::requireToken)
      val nonce =
        if (registration.protocol == OAuth2Protocol.OIDC) {
          nonceFactory().also(::requireToken)
        } else {
          null
        }
      val pending =
        OAuth2PendingAuthorization(
          state = state,
          registrationId = registrationId,
          redirectUri = redirectUri,
          browserBinding = browserBinding,
          nonce = nonce,
          expiresAtMillis = now + authorizationTtlMillis,
        )
      if (pendingAuthorizations.save(pending)) {
        val authorizationUrl =
          try {
            identityGateway.authorizationUrl(
              registration = registration,
              redirectUri = redirectUri,
              state = state,
              nonce = nonce,
            )
          } catch (failure: Throwable) {
            pendingAuthorizations.consume(state)
            throw failure
          }
        return OAuth2AuthorizationLaunch(
          redirectUri = authorizationUrl,
          state = state,
          browserBinding = browserBinding,
        )
      }
    }
    error("Failed to generate a unique OAuth2 authorization state")
  }

  suspend fun complete(
    registrationId: String,
    state: String,
    authorizationCode: String,
    browserBinding: String,
  ): User {
    val registration = registration(registrationId)
    if (state.isBlank() || authorizationCode.isBlank()) {
      throw OAuth2LoginException(INVALID_AUTHORIZATION_RESPONSE)
    }
    val pending =
      pendingAuthorizations.consume(state)
        ?: throw OAuth2LoginException(INVALID_AUTHORIZATION_STATE)
    if (
      pending.registrationId != registrationId ||
      pending.expiresAtMillis <= now() ||
      !constantTimeEquals(pending.browserBinding, browserBinding)
    ) {
      throw OAuth2LoginException(INVALID_AUTHORIZATION_STATE)
    }
    val identity =
      identityGateway.exchange(
        registration = registration,
        redirectUri = pending.redirectUri,
        authorizationCode = authorizationCode,
        expectedNonce = pending.nonce,
      )
    val email =
      identity.email?.takeIf(String::isNotBlank)
        ?: throw OAuth2LoginException(
          if (registration.protocol == OAuth2Protocol.OIDC) {
            OIDC_EMAIL_MISSING
          } else {
            OAUTH2_EMAIL_MISSING
          },
        )
    if (registration.protocol == OAuth2Protocol.OIDC && oidcEmailVerificationEnabled) {
      when (identity.emailVerified) {
        null -> throw OAuth2LoginException(OIDC_EMAIL_VERIFICATION_MISSING)
        false -> throw OAuth2LoginException(OIDC_EMAIL_NOT_VERIFIED)
        true -> Unit
      }
    }
    users.findByEmailIgnoreCaseOrNull(email)?.let { return it }
    if (!accountCreationEnabled) throw OAuth2LoginException(ACCOUNT_CREATION_DISABLED)
    return users.createUser(
      email = email,
      rawPassword =
        randomPasswordFactory().also {
          require(it.isNotBlank()) { "Generated OAuth2 account password must not be blank" }
        },
    )
  }

  private fun registration(registrationId: String): OAuth2ClientRegistration =
    registrations[registrationId] ?: throw OAuth2LoginException(UNKNOWN_REGISTRATION)

  private fun now(): Long =
    currentTimeMillis().also {
      require(it >= 0) { "OAuth2 timestamp must not be negative" }
      require(it <= Long.MAX_VALUE - authorizationTtlMillis) {
        "OAuth2 authorization expiry timestamp overflow"
      }
    }

  private fun requireToken(value: String) {
    require(value.isNotBlank()) { "Generated OAuth2 token must not be blank" }
  }

  private fun constantTimeEquals(
    expected: String,
    actual: String,
  ): Boolean {
    var difference = expected.length xor actual.length
    val length = maxOf(expected.length, actual.length)
    repeat(length) { index ->
      val left = expected.getOrNull(index)?.code ?: 0
      val right = actual.getOrNull(index)?.code ?: 0
      difference = difference or (left xor right)
    }
    return difference == 0
  }

  companion object {
    const val OAUTH2_EMAIL_MISSING = "ERR_1024"
    const val ACCOUNT_CREATION_DISABLED = "ERR_1025"
    const val OIDC_EMAIL_NOT_VERIFIED = "ERR_1026"
    const val OIDC_EMAIL_VERIFICATION_MISSING = "ERR_1027"
    const val OIDC_EMAIL_MISSING = "ERR_1028"
    const val INVALID_AUTHORIZATION_STATE = "invalid_state"
    const val INVALID_AUTHORIZATION_RESPONSE = "invalid_authorization_response"
    const val UNKNOWN_REGISTRATION = "unknown_registration"
    const val MAX_TOKEN_GENERATION_ATTEMPTS = 10
  }
}

private fun String.isWebUri(): Boolean =
  startsWith("https://") || startsWith("http://")
