package io.xoboro.server

import io.xoboro.core.application.OAuth2ClientAuthenticationMethod
import io.xoboro.core.application.OAuth2ClientRegistration
import io.xoboro.core.application.OAuth2Protocol

object OAuth2EnvironmentConfig {
  fun registrations(environment: Map<String, String>): List<OAuth2ClientRegistration> =
    environment.keys
      .mapNotNull(REGISTRATION_CLIENT_ID::matchEntire)
      .map { it.groupValues[1] }
      .distinct()
      .sorted()
      .map { registrationToken -> registration(environment, registrationToken) }

  private fun registration(
    environment: Map<String, String>,
    registrationToken: String,
  ): OAuth2ClientRegistration {
    val registrationPrefix =
      "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_${registrationToken}_"
    val registrationId = registrationToken.lowercase().replace('_', '-')
    val providerToken =
      environment["${registrationPrefix}PROVIDER"]
        ?.uppercase()
        ?: registrationToken
    val providerPrefix = "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_${providerToken}_"
    val builtIn = BUILT_INS[providerToken.lowercase()]
    val scopes =
      environment["${registrationPrefix}SCOPE"]
        ?.split(',', ' ')
        ?.filter(String::isNotBlank)
        ?: builtIn?.scopes
        ?: emptyList()
    val issuer =
      environment["${providerPrefix}ISSUER_URI"]
        ?.trimEnd('/')
        ?: builtIn?.issuerUri
    val protocol =
      if ("openid" in scopes || issuer != null) OAuth2Protocol.OIDC else OAuth2Protocol.OAUTH2
    return OAuth2ClientRegistration(
      registrationId = registrationId,
      clientName =
        environment["${registrationPrefix}CLIENT_NAME"]
          ?.takeIf(String::isNotBlank)
          ?: builtIn?.clientName
          ?: registrationId,
      clientId = environment.required("${registrationPrefix}CLIENT_ID"),
      clientSecret = environment.required("${registrationPrefix}CLIENT_SECRET"),
      authorizationUri =
        environment["${providerPrefix}AUTHORIZATION_URI"]
          ?: builtIn?.authorizationUri
          ?: requiredForOAuth2(protocol, "authorization URI", registrationId),
      tokenUri =
        environment["${providerPrefix}TOKEN_URI"]
          ?: builtIn?.tokenUri
          ?: requiredForOAuth2(protocol, "token URI", registrationId),
      userInfoUri =
        environment["${providerPrefix}USER_INFO_URI"]
          ?: builtIn?.userInfoUri
          ?: requiredForOAuth2(protocol, "user-info URI", registrationId),
      scopes = scopes,
      protocol = protocol,
      issuerUri = issuer,
      jwkSetUri =
        environment["${providerPrefix}JWK_SET_URI"]
          ?: builtIn?.jwkSetUri,
      clientAuthenticationMethod =
        when (
          environment["${registrationPrefix}CLIENT_AUTHENTICATION_METHOD"]
            ?.lowercase()
        ) {
          null, "client_secret_basic" -> OAuth2ClientAuthenticationMethod.CLIENT_SECRET_BASIC
          "client_secret_post" -> OAuth2ClientAuthenticationMethod.CLIENT_SECRET_POST
          else -> error("Unsupported OAuth2 client authentication method for $registrationId")
        },
    )
  }

  private fun Map<String, String>.required(key: String): String =
    get(key)?.takeIf(String::isNotBlank) ?: error("Missing required OAuth2 setting: $key")

  private fun requiredForOAuth2(
    protocol: OAuth2Protocol,
    setting: String,
    registrationId: String,
  ): String? =
    if (protocol == OAuth2Protocol.OAUTH2) {
      error("Missing OAuth2 $setting for $registrationId")
    } else {
      null
    }

  private data class BuiltInProvider(
    val clientName: String,
    val authorizationUri: String,
    val tokenUri: String,
    val userInfoUri: String,
    val scopes: List<String>,
    val issuerUri: String? = null,
    val jwkSetUri: String? = null,
  )

  private val REGISTRATION_CLIENT_ID =
    Regex("^SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_([A-Z0-9_]+)_CLIENT_ID$")

  private val BUILT_INS =
    mapOf(
      "github" to
        BuiltInProvider(
          clientName = "GitHub",
          authorizationUri = "https://github.com/login/oauth/authorize",
          tokenUri = "https://github.com/login/oauth/access_token",
          userInfoUri = "https://api.github.com/user",
          scopes = listOf("read:user"),
        ),
      "google" to
        BuiltInProvider(
          clientName = "Google",
          authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth",
          tokenUri = "https://oauth2.googleapis.com/token",
          userInfoUri = "https://openidconnect.googleapis.com/v1/userinfo",
          scopes = listOf("openid", "profile", "email"),
          issuerUri = "https://accounts.google.com",
          jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs",
        ),
      "facebook" to
        BuiltInProvider(
          clientName = "Facebook",
          authorizationUri = "https://www.facebook.com/v20.0/dialog/oauth",
          tokenUri = "https://graph.facebook.com/v20.0/oauth/access_token",
          userInfoUri = "https://graph.facebook.com/me?fields=id,name,email",
          scopes = listOf("public_profile", "email"),
        ),
    )
}
