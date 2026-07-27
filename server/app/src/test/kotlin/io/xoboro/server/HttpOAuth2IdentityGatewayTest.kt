package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.xoboro.core.application.OAuth2ClientRegistration
import io.xoboro.core.application.OAuth2LoginException
import io.xoboro.core.application.OAuth2Protocol
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class HttpOAuth2IdentityGatewayTest {
  @Test
  fun `builds an encoded authorization-code request`() = runBlocking {
    val gateway = HttpOAuth2IdentityGateway(HttpClient(MockEngine { error("unused") }))
    val url =
      Url(
        gateway.authorizationUrl(
          registration = registration(),
          redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
          state = "synthetic state",
          nonce = "synthetic nonce",
        ),
      )

    assertEquals("code", url.parameters["response_type"])
    assertEquals("synthetic-client", url.parameters["client_id"])
    assertEquals("openid profile email", url.parameters["scope"])
    assertEquals("synthetic state", url.parameters["state"])
    assertEquals("synthetic nonce", url.parameters["nonce"])
  }

  @Test
  fun `verifies an OIDC token before accepting user info`() = runBlocking {
    val keyPair = rsaKeyPair()
    val idToken =
      idToken(
        keyPair = keyPair,
        nonce = "synthetic-nonce",
        expiresAtSeconds = 2_000,
      )
    val requestedPaths = mutableListOf<String>()
    val client =
      jsonClient { path ->
        requestedPaths += path
        when (path) {
          "/token" -> """{"access_token":"synthetic-access","id_token":"$idToken"}"""
          "/jwks" -> jwkSet(keyPair)
          "/userinfo" ->
            """{"email":"reader@example.invalid","email_verified":true}"""
          else -> error("Unexpected path: $path")
        }
      }

    val identity =
      HttpOAuth2IdentityGateway(client, currentTimeMillis = { 1_000_000 })
        .exchange(
          registration = registration(),
          redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
          authorizationCode = "synthetic-code",
          expectedNonce = "synthetic-nonce",
        )

    assertEquals("reader@example.invalid", identity.email)
    assertEquals(true, identity.emailVerified)
    assertEquals(listOf("/token", "/jwks", "/userinfo"), requestedPaths)
  }

  @Test
  fun `rejects a signed OIDC token with the wrong nonce`() {
    val keyPair = rsaKeyPair()
    val client =
      jsonClient { path ->
        when (path) {
          "/token" ->
            """{"access_token":"synthetic-access","id_token":"${
              idToken(keyPair, nonce = "wrong-nonce", expiresAtSeconds = 2_000)
            }"}"""
          "/jwks" -> jwkSet(keyPair)
          else -> error("User info must not be requested")
        }
      }

    val failure =
      assertFailsWith<OAuth2LoginException> {
        runBlocking {
          HttpOAuth2IdentityGateway(client, currentTimeMillis = { 1_000_000 })
            .exchange(
              registration = registration(),
              redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
              authorizationCode = "synthetic-code",
              expectedNonce = "synthetic-nonce",
            )
        }
      }
    assertEquals("invalid_id_token_nonce", failure.errorCode)
  }

  @Test
  fun `discovers and caches an issuer provider configuration`() = runBlocking {
    val keyPair = rsaKeyPair()
    val idToken =
      idToken(
        keyPair = keyPair,
        nonce = "synthetic-nonce",
        expiresAtSeconds = 2_000,
      )
    val requestedPaths = mutableListOf<String>()
    val client =
      jsonClient { path ->
        requestedPaths += path
        when (path) {
          "/.well-known/openid-configuration" ->
            """
            {
              "issuer":"https://identity.example.invalid",
              "authorization_endpoint":"https://identity.example.invalid/discovered-authorize",
              "token_endpoint":"https://identity.example.invalid/discovered-token",
              "userinfo_endpoint":"https://identity.example.invalid/discovered-userinfo",
              "jwks_uri":"https://identity.example.invalid/discovered-jwks"
            }
            """.trimIndent()
          "/discovered-token" ->
            """{"access_token":"synthetic-access","id_token":"$idToken"}"""
          "/discovered-jwks" -> jwkSet(keyPair)
          "/discovered-userinfo" ->
            """{"email":"reader@example.invalid","email_verified":true}"""
          else -> error("Unexpected path: $path")
        }
      }
    val gateway = HttpOAuth2IdentityGateway(client, currentTimeMillis = { 1_000_000 })
    val discovered =
      registration().copy(
        authorizationUri = null,
        tokenUri = null,
        userInfoUri = null,
        jwkSetUri = null,
      )

    val authorization =
      Url(
        gateway.authorizationUrl(
          discovered,
          "https://reader.example.invalid/login/oauth2/code/synthetic",
          "synthetic-state",
          "synthetic-nonce",
        ),
      )
    val identity =
      gateway.exchange(
        registration = discovered,
        redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
        authorizationCode = "synthetic-code",
        expectedNonce = "synthetic-nonce",
      )

    assertEquals("/discovered-authorize", authorization.encodedPath)
    assertEquals("reader@example.invalid", identity.email)
    assertEquals(
      listOf(
        "/.well-known/openid-configuration",
        "/discovered-token",
        "/discovered-jwks",
        "/discovered-userinfo",
      ),
      requestedPaths,
    )
  }

  @Test
  fun `rejects discovery metadata for a different issuer`() {
    val client =
      jsonClient { path ->
        check(path == "/.well-known/openid-configuration")
        """
        {
          "issuer":"https://attacker.example.invalid",
          "authorization_endpoint":"https://attacker.example.invalid/authorize",
          "token_endpoint":"https://attacker.example.invalid/token",
          "userinfo_endpoint":"https://attacker.example.invalid/userinfo",
          "jwks_uri":"https://attacker.example.invalid/jwks"
        }
        """.trimIndent()
      }
    val discovered =
      registration().copy(
        authorizationUri = null,
        tokenUri = null,
        userInfoUri = null,
        jwkSetUri = null,
      )

    val failure =
      assertFailsWith<OAuth2LoginException> {
        runBlocking {
          HttpOAuth2IdentityGateway(client)
            .authorizationUrl(
              discovered,
              "https://reader.example.invalid/login/oauth2/code/synthetic",
              "synthetic-state",
              "synthetic-nonce",
            )
        }
      }

    assertEquals("invalid_oidc_discovery_issuer", failure.errorCode)
  }

  @Test
  fun `verifies EC signed OIDC tokens`() = runBlocking {
    val cases =
      listOf(
        EcCase("ES256", "secp256r1", "P-256", 64, 32),
        EcCase("ES384", "secp384r1", "P-384", 96, 48),
        EcCase("ES512", "secp521r1", "P-521", 132, 66),
      )

    cases.forEach { case ->
      val keyPair = ecKeyPair(case.jcaCurve)
      val idToken =
        idToken(
          keyPair = keyPair,
          nonce = "synthetic-nonce",
          expiresAtSeconds = 2_000,
          joseAlgorithm = case.joseAlgorithm,
          signatureSize = case.signatureSize,
        )
      val client =
        jsonClient { path ->
          when (path) {
            "/token" ->
              """{"access_token":"synthetic-access","id_token":"$idToken"}"""
            "/jwks" -> ecJwkSet(keyPair, case.joseCurve, case.coordinateSize)
            "/userinfo" ->
              """{"email":"reader@example.invalid","email_verified":true}"""
            else -> error("Unexpected path: $path")
          }
        }

      val identity =
        HttpOAuth2IdentityGateway(client, currentTimeMillis = { 1_000_000 })
          .exchange(
            registration = registration(),
            redirectUri = "https://reader.example.invalid/login/oauth2/code/synthetic",
            authorizationCode = "synthetic-code",
            expectedNonce = "synthetic-nonce",
          )

      assertEquals("reader@example.invalid", identity.email, case.joseAlgorithm)
    }
  }

  private fun jsonClient(response: (String) -> String): HttpClient =
    HttpClient(
      MockEngine { request ->
        respond(
          content = response(request.url.encodedPath),
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      },
    ) {
      expectSuccess = true
      install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
      }
    }

  private fun idToken(
    keyPair: KeyPair,
    nonce: String,
    expiresAtSeconds: Long,
    joseAlgorithm: String = "RS256",
    signatureSize: Int? = null,
  ): String {
    val header = """{"alg":"$joseAlgorithm","kid":"synthetic-key","typ":"JWT"}""".base64Url()
    val claims =
      """{"iss":"https://identity.example.invalid","sub":"synthetic-subject","aud":"synthetic-client","exp":$expiresAtSeconds,"nonce":"$nonce"}"""
        .base64Url()
    val derOrRsaSignature =
      Signature
        .getInstance(
          when (joseAlgorithm) {
            "RS256" -> "SHA256withRSA"
            "ES256" -> "SHA256withECDSA"
            "ES384" -> "SHA384withECDSA"
            "ES512" -> "SHA512withECDSA"
            else -> error("Unsupported synthetic algorithm")
          },
        )
        .apply {
          initSign(keyPair.private)
          update("$header.$claims".toByteArray(Charsets.US_ASCII))
        }.sign()
    val signature =
      signatureSize
        ?.let { derOrRsaSignature.toJoseEcdsaSignature(it) }
        ?: derOrRsaSignature
    return "$header.$claims.${signature.base64Url()}"
  }

  private fun jwkSet(keyPair: KeyPair): String {
    val publicKey = keyPair.public as RSAPublicKey
    return """{"keys":[{"kty":"RSA","kid":"synthetic-key","n":"${
      publicKey.modulus.toUnsignedBytes().base64Url()
    }","e":"${publicKey.publicExponent.toUnsignedBytes().base64Url()}"}]}"""
  }

  private fun rsaKeyPair(): KeyPair =
    KeyPairGenerator
      .getInstance("RSA")
      .apply { initialize(2_048) }
      .generateKeyPair()

  private fun ecKeyPair(curve: String): KeyPair =
    KeyPairGenerator
      .getInstance("EC")
      .apply { initialize(ECGenParameterSpec(curve)) }
      .generateKeyPair()

  private fun ecJwkSet(
    keyPair: KeyPair,
    curve: String,
    coordinateSize: Int,
  ): String {
    val publicKey = keyPair.public as ECPublicKey
    return """{"keys":[{"kty":"EC","kid":"synthetic-key","crv":"$curve","x":"${
      publicKey.w.affineX.toFixedUnsignedBytes(coordinateSize).base64Url()
    }","y":"${publicKey.w.affineY.toFixedUnsignedBytes(coordinateSize).base64Url()}"}]}"""
  }

  private fun ByteArray.toJoseEcdsaSignature(size: Int): ByteArray {
    var cursor = 0
    check(this[cursor++].toInt() and 0xff == 0x30)
    cursor += derLengthBytes(cursor)
    check(this[cursor++].toInt() and 0xff == 0x02)
    val rLength = derLength(cursor)
    cursor += derLengthBytes(cursor)
    val r = copyOfRange(cursor, cursor + rLength)
    cursor += rLength
    check(this[cursor++].toInt() and 0xff == 0x02)
    val sLength = derLength(cursor)
    cursor += derLengthBytes(cursor)
    val s = copyOfRange(cursor, cursor + sLength)
    val componentSize = size / 2
    return r.toFixedUnsigned(componentSize) + s.toFixedUnsigned(componentSize)
  }

  private fun ByteArray.derLength(offset: Int): Int {
    val first = this[offset].toInt() and 0xff
    if (first < 0x80) return first
    val count = first and 0x7f
    return (1..count).fold(0) { value, index ->
      (value shl 8) or (this[offset + index].toInt() and 0xff)
    }
  }

  private fun ByteArray.derLengthBytes(offset: Int): Int {
    val first = this[offset].toInt() and 0xff
    return if (first < 0x80) 1 else 1 + (first and 0x7f)
  }

  private fun ByteArray.toFixedUnsigned(size: Int): ByteArray {
    val unsigned = dropWhile { it == 0.toByte() }.toByteArray()
    check(unsigned.size <= size)
    return ByteArray(size - unsigned.size) + unsigned
  }

  private fun String.base64Url(): String = toByteArray().base64Url()

  private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

  private fun java.math.BigInteger.toUnsignedBytes(): ByteArray =
    toByteArray().let {
      if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

  private fun java.math.BigInteger.toFixedUnsignedBytes(size: Int): ByteArray =
    toUnsignedBytes().let {
      check(it.size <= size)
      ByteArray(size - it.size) + it
    }

  private data class EcCase(
    val joseAlgorithm: String,
    val jcaCurve: String,
    val joseCurve: String,
    val signatureSize: Int,
    val coordinateSize: Int,
  )

  private companion object {
    fun registration(): OAuth2ClientRegistration =
      OAuth2ClientRegistration(
        registrationId = "synthetic",
        clientName = "Synthetic Identity",
        clientId = "synthetic-client",
        clientSecret = "synthetic-secret",
        authorizationUri = "https://identity.example.invalid/authorize",
        tokenUri = "https://identity.example.invalid/token",
        userInfoUri = "https://identity.example.invalid/userinfo",
        scopes = listOf("openid", "profile", "email"),
        protocol = OAuth2Protocol.OIDC,
        issuerUri = "https://identity.example.invalid",
        jwkSetUri = "https://identity.example.invalid/jwks",
      )
  }
}
