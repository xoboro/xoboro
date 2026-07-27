package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.xoboro.core.application.OAuth2ClientAuthenticationMethod
import io.xoboro.core.application.OAuth2ClientRegistration
import io.xoboro.core.application.OAuth2ExternalIdentity
import io.xoboro.core.application.OAuth2IdentityGateway
import io.xoboro.core.application.OAuth2LoginException
import io.xoboro.core.application.OAuth2Protocol
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class HttpOAuth2IdentityGateway(
  private val client: HttpClient,
  private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : OAuth2IdentityGateway {
  private val jwkSets = ConcurrentHashMap<String, JsonObject>()

  override fun authorizationUrl(
    registration: OAuth2ClientRegistration,
    redirectUri: String,
    state: String,
    nonce: String?,
  ): String =
    URLBuilder(registration.authorizationUri)
      .apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", registration.clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("state", state)
        if (registration.scopes.isNotEmpty()) {
          parameters.append("scope", registration.scopes.joinToString(" "))
        }
        nonce?.let { parameters.append("nonce", it) }
      }.buildString()

  override suspend fun exchange(
    registration: OAuth2ClientRegistration,
    redirectUri: String,
    authorizationCode: String,
    expectedNonce: String?,
  ): OAuth2ExternalIdentity {
    val token =
      client
        .submitForm(
          url = registration.tokenUri,
          formParameters =
            Parameters.build {
              append("grant_type", "authorization_code")
              append("code", authorizationCode)
              append("redirect_uri", redirectUri)
              append("client_id", registration.clientId)
              if (
                registration.clientAuthenticationMethod ==
                  OAuth2ClientAuthenticationMethod.CLIENT_SECRET_POST
              ) {
                append("client_secret", registration.clientSecret)
              }
            },
        ) {
          accept(ContentType.Application.Json)
          if (
            registration.clientAuthenticationMethod ==
              OAuth2ClientAuthenticationMethod.CLIENT_SECRET_BASIC
          ) {
            basicAuth(registration.clientId, registration.clientSecret)
          }
        }.body<JsonObject>()
    val accessToken =
      token.string("access_token")
        ?: throw OAuth2LoginException("missing_access_token")
    if (registration.protocol == OAuth2Protocol.OIDC) {
      validateIdToken(
        registration = registration,
        encodedToken =
          token.string("id_token")
            ?: throw OAuth2LoginException("missing_id_token"),
        expectedNonce = requireNotNull(expectedNonce),
      )
    }
    val userInfo =
      client.get(registration.userInfoUri) {
        accept(ContentType.Application.Json)
        bearerAuth(accessToken)
      }.body<JsonObject>()
    var email = userInfo.string("email")
    var emailVerified = userInfo["email_verified"]?.jsonPrimitive?.booleanOrNull
    if (
      registration.registrationId.equals("github", ignoreCase = true) &&
      email == null &&
      registration.scopes.any { it == "user" || it == "user:email" }
    ) {
      val primary =
        client.get("${registration.userInfoUri.trimEnd('/')}/emails") {
          accept(ContentType.Application.Json)
          bearerAuth(accessToken)
        }.body<JsonArray>()
          .map(JsonElement::jsonObject)
          .firstOrNull {
            it["verified"]?.jsonPrimitive?.booleanOrNull == true &&
              it["primary"]?.jsonPrimitive?.booleanOrNull == true
          }
      email = primary?.string("email")
      emailVerified = primary?.get("verified")?.jsonPrimitive?.booleanOrNull
    }
    return OAuth2ExternalIdentity(email = email, emailVerified = emailVerified)
  }

  private suspend fun validateIdToken(
    registration: OAuth2ClientRegistration,
    encodedToken: String,
    expectedNonce: String,
  ) {
    val parts = encodedToken.split('.')
    if (parts.size != 3) throw OAuth2LoginException("invalid_id_token")
    val header = decodeJson(parts[0])
    val claims = decodeJson(parts[1])
    val algorithm =
      header.string("alg")
        ?.let(RSA_SIGNATURE_ALGORITHMS::get)
        ?: throw OAuth2LoginException("unsupported_id_token_algorithm")
    val keyId =
      header.string("kid")
        ?: throw OAuth2LoginException("missing_id_token_key")
    val key = findJwk(registration, keyId)
    key.string("alg")?.let {
      if (RSA_SIGNATURE_ALGORITHMS[it] != algorithm) {
        throw OAuth2LoginException("invalid_id_token_key_algorithm")
      }
    }
    val modulus = key.string("n")?.decodeBase64UrlUnsigned()
    val exponent = key.string("e")?.decodeBase64UrlUnsigned()
    if (modulus == null || exponent == null) throw OAuth2LoginException("invalid_id_token_key")
    val publicKey =
      KeyFactory
        .getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(modulus, exponent))
    val validSignature =
      Signature
        .getInstance(algorithm)
        .apply {
          initVerify(publicKey)
          update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }.verify(parts[2].decodeBase64Url())
    if (!validSignature) throw OAuth2LoginException("invalid_id_token_signature")
    if (claims.string("iss") != registration.issuerUri) {
      throw OAuth2LoginException("invalid_id_token_issuer")
    }
    val audiences =
      when (val audience = claims["aud"]) {
        is JsonArray -> audience.map { it.jsonPrimitive.content }
        else -> listOfNotNull(audience?.jsonPrimitive?.content)
      }
    if (registration.clientId !in audiences) {
      throw OAuth2LoginException("invalid_id_token_audience")
    }
    if (audiences.size > 1 && claims.string("azp") != registration.clientId) {
      throw OAuth2LoginException("invalid_id_token_authorized_party")
    }
    val nowSeconds = currentTimeMillis().also { require(it >= 0) } / 1_000
    val expiry = claims["exp"]?.jsonPrimitive?.longOrNull
    if (expiry == null || expiry <= nowSeconds - CLOCK_SKEW_SECONDS) {
      throw OAuth2LoginException("expired_id_token")
    }
    claims["nbf"]?.jsonPrimitive?.longOrNull?.let {
      if (it > nowSeconds + CLOCK_SKEW_SECONDS) {
        throw OAuth2LoginException("id_token_not_active")
      }
    }
    claims["iat"]?.jsonPrimitive?.longOrNull?.let {
      if (it > nowSeconds + CLOCK_SKEW_SECONDS) {
        throw OAuth2LoginException("invalid_id_token_issued_at")
      }
    }
    if (claims.string("sub") == null) {
      throw OAuth2LoginException("missing_id_token_subject")
    }
    val nonce = claims.string("nonce")
    if (
      nonce == null ||
      !MessageDigest.isEqual(
        nonce.toByteArray(Charsets.UTF_8),
        expectedNonce.toByteArray(Charsets.UTF_8),
      )
    ) {
      throw OAuth2LoginException("invalid_id_token_nonce")
    }
  }

  private suspend fun findJwk(
    registration: OAuth2ClientRegistration,
    keyId: String,
  ): JsonObject {
    val uri = requireNotNull(registration.jwkSetUri)
    fun JsonObject.find(): JsonObject? =
      get("keys")
        ?.let { it as? JsonArray }
        ?.map { it.jsonObject }
        ?.firstOrNull { it.string("kid") == keyId && it.string("kty") == "RSA" }
    jwkSets[uri]?.find()?.let { return it }
    val refreshed = client.get(uri) {
      accept(ContentType.Application.Json)
    }.body<JsonObject>()
    jwkSets[uri] = refreshed
    return refreshed.find() ?: throw OAuth2LoginException("unknown_id_token_key")
  }

  private fun decodeJson(value: String): JsonObject =
    runCatching {
      Json.parseToJsonElement(value.decodeBase64Url().decodeToString()).jsonObject
    }.getOrElse {
      throw OAuth2LoginException("invalid_id_token")
    }

  private fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.content?.takeIf(String::isNotBlank)

  private fun String.decodeBase64Url(): ByteArray =
    runCatching { Base64.getUrlDecoder().decode(this) }
      .getOrElse { throw OAuth2LoginException("invalid_base64url") }

  private fun String.decodeBase64UrlUnsigned(): BigInteger =
    BigInteger(1, decodeBase64Url())

  private companion object {
    const val CLOCK_SKEW_SECONDS = 60
    val RSA_SIGNATURE_ALGORITHMS =
      mapOf(
        "RS256" to "SHA256withRSA",
        "RS384" to "SHA384withRSA",
        "RS512" to "SHA512withRSA",
      )
  }
}
