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
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class HttpOAuth2IdentityGatewayTest {
  @Test
  fun `builds an encoded authorization-code request`() {
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
  ): String {
    val header = """{"alg":"RS256","kid":"synthetic-key","typ":"JWT"}""".base64Url()
    val claims =
      """{"iss":"https://identity.example.invalid","sub":"synthetic-subject","aud":"synthetic-client","exp":$expiresAtSeconds,"nonce":"$nonce"}"""
        .base64Url()
    val signature =
      Signature
        .getInstance("SHA256withRSA")
        .apply {
          initSign(keyPair.private)
          update("$header.$claims".toByteArray(Charsets.US_ASCII))
        }.sign()
        .base64Url()
    return "$header.$claims.$signature"
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

  private fun String.base64Url(): String = toByteArray().base64Url()

  private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

  private fun java.math.BigInteger.toUnsignedBytes(): ByteArray =
    toByteArray().let {
      if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

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
