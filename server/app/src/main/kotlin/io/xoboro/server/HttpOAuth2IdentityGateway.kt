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
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
  private val providerMetadata = ConcurrentHashMap<String, JsonObject>()

  override suspend fun authorizationUrl(
    registration: OAuth2ClientRegistration,
    redirectUri: String,
    state: String,
    nonce: String?,
  ): String {
    val resolved = resolve(registration)
    return URLBuilder(requireNotNull(resolved.authorizationUri))
      .apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", resolved.clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("state", state)
        if (resolved.scopes.isNotEmpty()) {
          parameters.append("scope", resolved.scopes.joinToString(" "))
        }
        nonce?.let { parameters.append("nonce", it) }
      }.buildString()
  }

  override suspend fun exchange(
    registration: OAuth2ClientRegistration,
    redirectUri: String,
    authorizationCode: String,
    expectedNonce: String?,
  ): OAuth2ExternalIdentity {
    val resolved = resolve(registration)
    val token =
      client
        .submitForm(
          url = requireNotNull(resolved.tokenUri),
          formParameters =
            Parameters.build {
              append("grant_type", "authorization_code")
              append("code", authorizationCode)
              append("redirect_uri", redirectUri)
              append("client_id", resolved.clientId)
              if (
                resolved.clientAuthenticationMethod ==
                  OAuth2ClientAuthenticationMethod.CLIENT_SECRET_POST
              ) {
                append("client_secret", resolved.clientSecret)
              }
            },
        ) {
          accept(ContentType.Application.Json)
          if (
            resolved.clientAuthenticationMethod ==
              OAuth2ClientAuthenticationMethod.CLIENT_SECRET_BASIC
          ) {
            basicAuth(resolved.clientId, resolved.clientSecret)
          }
        }.body<JsonObject>()
    val accessToken =
      token.string("access_token")
        ?: throw OAuth2LoginException("missing_access_token")
    if (resolved.protocol == OAuth2Protocol.OIDC) {
      validateIdToken(
        registration = resolved,
        encodedToken =
          token.string("id_token")
            ?: throw OAuth2LoginException("missing_id_token"),
        expectedNonce = requireNotNull(expectedNonce),
      )
    }
    val userInfo =
      client.get(requireNotNull(resolved.userInfoUri)) {
        accept(ContentType.Application.Json)
        bearerAuth(accessToken)
      }.body<JsonObject>()
    var email = userInfo.string("email")
    var emailVerified = userInfo["email_verified"]?.jsonPrimitive?.booleanOrNull
    if (
      resolved.registrationId.equals("github", ignoreCase = true) &&
      email == null &&
      resolved.scopes.any { it == "user" || it == "user:email" }
    ) {
      val primary =
        client.get("${requireNotNull(resolved.userInfoUri).trimEnd('/')}/emails") {
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
    val algorithmName =
      header.string("alg")
        ?: throw OAuth2LoginException("unsupported_id_token_algorithm")
    val algorithm =
      SIGNATURE_ALGORITHMS[algorithmName]
        ?: throw OAuth2LoginException("unsupported_id_token_algorithm")
    val keyId =
      header.string("kid")
        ?: throw OAuth2LoginException("missing_id_token_key")
    val key = findJwk(registration, keyId, algorithm.keyType)
    key.string("alg")?.let {
      if (it != algorithmName) {
        throw OAuth2LoginException("invalid_id_token_key_algorithm")
      }
    }
    key.string("use")?.let {
      if (it != "sig") throw OAuth2LoginException("invalid_id_token_key_use")
    }
    (key["key_ops"] as? JsonArray)?.let { operations ->
      if (operations.none { it.jsonPrimitive.content == "verify" }) {
        throw OAuth2LoginException("invalid_id_token_key_use")
      }
    }
    val publicKey = key.toPublicKey(algorithm)
    val encodedSignature = parts[2].decodeBase64Url()
    val signature =
      if (algorithm.keyType == "EC") {
        encodedSignature.toDerEcdsaSignature(algorithm.signatureSize)
      } else {
        encodedSignature
      }
    val validSignature =
      runCatching {
        Signature
          .getInstance(algorithm.jcaName)
          .apply {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
          }.verify(signature)
      }.getOrDefault(false)
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
    keyType: String,
  ): JsonObject {
    val uri = requireNotNull(registration.jwkSetUri)
    fun JsonObject.find(): JsonObject? =
      get("keys")
        ?.let { it as? JsonArray }
        ?.map { it.jsonObject }
        ?.firstOrNull { it.string("kid") == keyId && it.string("kty") == keyType }
    jwkSets[uri]?.find()?.let { return it }
    val refreshed = client.get(uri) {
      accept(ContentType.Application.Json)
    }.body<JsonObject>()
    jwkSets[uri] = refreshed
    return refreshed.find() ?: throw OAuth2LoginException("unknown_id_token_key")
  }

  private suspend fun resolve(registration: OAuth2ClientRegistration): OAuth2ClientRegistration {
    if (
      registration.protocol != OAuth2Protocol.OIDC ||
      (
        registration.authorizationUri != null &&
          registration.tokenUri != null &&
          registration.userInfoUri != null &&
          registration.jwkSetUri != null
      )
    ) {
      return registration
    }
    val issuer = requireNotNull(registration.issuerUri)
    val metadata =
      providerMetadata[issuer]
        ?: discover(issuer.trimEnd('/')).also { providerMetadata[issuer] = it }
    if (metadata.string("issuer") != issuer) {
      throw OAuth2LoginException("invalid_oidc_discovery_issuer")
    }
    return runCatching {
      registration.copy(
        authorizationUri =
          registration.authorizationUri ?: metadata.requiredWebUri("authorization_endpoint"),
        tokenUri = registration.tokenUri ?: metadata.requiredWebUri("token_endpoint"),
        userInfoUri = registration.userInfoUri ?: metadata.requiredWebUri("userinfo_endpoint"),
        jwkSetUri = registration.jwkSetUri ?: metadata.requiredWebUri("jwks_uri"),
      )
    }.getOrElse {
      if (it is OAuth2LoginException) throw it
      throw OAuth2LoginException("invalid_oidc_provider_configuration")
    }
  }

  private suspend fun discover(issuer: String): JsonObject =
    try {
      client
        .get("$issuer/.well-known/openid-configuration") {
          accept(ContentType.Application.Json)
        }.body()
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: OAuth2LoginException) {
      throw failure
    } catch (_: Exception) {
      throw OAuth2LoginException("oidc_discovery_failed")
    }

  private fun JsonObject.requiredWebUri(key: String): String =
    string(key)
      ?.takeIf(::isWebUri)
      ?: throw OAuth2LoginException("invalid_oidc_provider_configuration")

  private fun isWebUri(value: String): Boolean =
    runCatching {
      val url = URLBuilder(value).build()
      (url.protocol.name == "http" || url.protocol.name == "https") &&
        url.host.isNotBlank()
    }.getOrDefault(false)

  private fun JsonObject.toPublicKey(algorithm: SigningAlgorithm): PublicKey =
    try {
      when (algorithm.keyType) {
        "RSA" -> {
          val modulus = string("n")?.decodeBase64UrlUnsigned()
          val exponent = string("e")?.decodeBase64UrlUnsigned()
          if (modulus == null || exponent == null) {
            throw OAuth2LoginException("invalid_id_token_key")
          }
          KeyFactory
            .getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        }
        "EC" -> {
          val curve =
            EC_CURVES[string("crv")]
              ?: throw OAuth2LoginException("invalid_id_token_key")
          if (curve.joseName != algorithm.curve) {
            throw OAuth2LoginException("invalid_id_token_key_algorithm")
          }
          val x = string("x")?.decodeBase64Url()
          val y = string("y")?.decodeBase64Url()
          if (
            x == null ||
            y == null ||
            x.size != curve.coordinateSize ||
            y.size != curve.coordinateSize
          ) {
            throw OAuth2LoginException("invalid_id_token_key")
          }
          val parameters =
            AlgorithmParameters
              .getInstance("EC")
              .apply { init(ECGenParameterSpec(curve.jcaName)) }
              .getParameterSpec(ECParameterSpec::class.java)
          KeyFactory
            .getInstance("EC")
            .generatePublic(
              ECPublicKeySpec(
                ECPoint(BigInteger(1, x), BigInteger(1, y)),
                parameters,
              ),
            )
        }
        else -> throw OAuth2LoginException("invalid_id_token_key")
      }
    } catch (failure: OAuth2LoginException) {
      throw failure
    } catch (_: Exception) {
      throw OAuth2LoginException("invalid_id_token_key")
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

  private fun ByteArray.toDerEcdsaSignature(expectedSize: Int): ByteArray {
    if (size != expectedSize || size % 2 != 0) {
      throw OAuth2LoginException("invalid_id_token_signature")
    }
    val componentSize = size / 2
    val r = copyOfRange(0, componentSize).toDerInteger()
    val s = copyOfRange(componentSize, size).toDerInteger()
    val values = r + s
    return byteArrayOf(0x30) + values.size.derLength() + values
  }

  private fun ByteArray.toDerInteger(): ByteArray {
    val firstValue = indexOfFirst { it != 0.toByte() }.let { if (it < 0) lastIndex else it }
    val unsigned = copyOfRange(firstValue, size)
    val value =
      if (unsigned.first().toInt() and 0x80 != 0) {
        byteArrayOf(0) + unsigned
      } else {
        unsigned
      }
    return byteArrayOf(0x02) + value.size.derLength() + value
  }

  private fun Int.derLength(): ByteArray =
    when {
      this < 0 -> throw OAuth2LoginException("invalid_id_token_signature")
      this < 128 -> byteArrayOf(toByte())
      this < 256 -> byteArrayOf(0x81.toByte(), toByte())
      else -> byteArrayOf(0x82.toByte(), (this shr 8).toByte(), toByte())
    }

  private data class SigningAlgorithm(
    val jcaName: String,
    val keyType: String,
    val curve: String? = null,
    val signatureSize: Int = 0,
  )

  private data class EcCurve(
    val joseName: String,
    val jcaName: String,
    val coordinateSize: Int,
  )

  private companion object {
    const val CLOCK_SKEW_SECONDS = 60
    val SIGNATURE_ALGORITHMS =
      mapOf(
        "RS256" to SigningAlgorithm("SHA256withRSA", "RSA"),
        "RS384" to SigningAlgorithm("SHA384withRSA", "RSA"),
        "RS512" to SigningAlgorithm("SHA512withRSA", "RSA"),
        "ES256" to SigningAlgorithm("SHA256withECDSA", "EC", "P-256", 64),
        "ES384" to SigningAlgorithm("SHA384withECDSA", "EC", "P-384", 96),
        "ES512" to SigningAlgorithm("SHA512withECDSA", "EC", "P-521", 132),
      )
    val EC_CURVES =
      listOf(
        EcCurve("P-256", "secp256r1", 32),
        EcCurve("P-384", "secp384r1", 48),
        EcCurve("P-521", "secp521r1", 66),
      ).associateBy(EcCurve::joseName)
  }
}
