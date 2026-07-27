package io.xoboro.server

import io.xoboro.core.application.OAuth2ClientAuthenticationMethod
import io.xoboro.core.application.OAuth2Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuth2EnvironmentConfigTest {
  @Test
  fun `parses Spring compatible built-in and custom registrations without exposing secrets`() {
    val registrations =
      OAuth2EnvironmentConfig.registrations(
        mapOf(
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID" to "synthetic-github-id",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET" to "github-secret",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_READER_CLIENT_ID" to "synthetic-reader-id",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_READER_CLIENT_SECRET" to "reader-secret",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_READER_CLIENT_NAME" to "Synthetic Identity",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_READER_PROVIDER" to "SYNTHETIC",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_READER_SCOPE" to "openid,profile,email",
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_READER_CLIENT_AUTHENTICATION_METHOD" to
            "client_secret_post",
          "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_SYNTHETIC_AUTHORIZATION_URI" to
            "https://identity.example.invalid/authorize",
          "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_SYNTHETIC_TOKEN_URI" to
            "https://identity.example.invalid/token",
          "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_SYNTHETIC_USER_INFO_URI" to
            "https://identity.example.invalid/userinfo",
          "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_SYNTHETIC_ISSUER_URI" to
            "https://identity.example.invalid",
          "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_SYNTHETIC_JWK_SET_URI" to
            "https://identity.example.invalid/jwks",
        ),
      )

    assertEquals(listOf("github", "reader"), registrations.map { it.registrationId })
    val github = registrations.first()
    assertEquals("GitHub", github.clientName)
    assertEquals(OAuth2Protocol.OAUTH2, github.protocol)
    val reader = registrations.last()
    assertEquals("Synthetic Identity", reader.clientName)
    assertEquals(OAuth2Protocol.OIDC, reader.protocol)
    assertEquals(
      OAuth2ClientAuthenticationMethod.CLIENT_SECRET_POST,
      reader.clientAuthenticationMethod,
    )
    assertFalse(reader.toString().contains("reader-secret"))
    assertTrue(reader.toString().contains("[REDACTED]"))
  }

  @Test
  fun `rejects incomplete registrations and invalid booleans`() {
    assertFailsWith<IllegalStateException> {
      OAuth2EnvironmentConfig.registrations(
        mapOf(
          "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SYNTHETIC_CLIENT_ID" to "synthetic-id",
        ),
      )
    }
    assertFailsWith<IllegalStateException> {
      ServerConfig.fromEnvironment(mapOf("KOMGA_OAUTH2_ACCOUNT_CREATION" to "yes"))
    }
  }

  @Test
  fun `binds Komga account policies through server config`() {
    val config =
      ServerConfig.fromEnvironment(
        environment =
          mapOf(
            "KOMGA_OAUTH2_ACCOUNT_CREATION" to "true",
            "KOMGA_OIDC_EMAIL_VERIFICATION" to "false",
          ),
      )

    assertTrue(config.oauth2AccountCreation)
    assertFalse(config.oidcEmailVerification)
  }
}
