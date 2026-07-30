package io.xoboro.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.xoboro.core.application.OAuth2LoginLifecycle
import kotlinx.serialization.Serializable

/**
 * Reports how external login is configured, to an administrator, read-only.
 *
 * **Read-only is the decision, not an omission.** Writing provider configuration through the API
 * would mean storing client secrets in the database, and the database is the one artifact that gets
 * backed up, copied to a laptop to debug a scan, and restored onto a staging host. Secrets belong in
 * the process environment, which is not any of those things. Registration therefore stays in
 * environment variables and this endpoint exists so an administrator can confirm what a *running*
 * deployment actually resolved — previously the only way to know was to read the environment on the
 * host, which is exactly what an operator with a browser and an admin account does not have.
 *
 * Only names and identifiers cross the wire. No client id, no client secret, no endpoint URI: those
 * would turn a configuration display into a credential disclosure, and an administrator who needs
 * them already has the environment that holds them.
 */
fun Route.xoboroNativeOAuth2Routes(oauth2: OAuth2LoginLifecycle) {
  route(XOBORO_API_PREFIX) {
    authenticate(
      XOBORO_BEARER_AUTHENTICATION,
      XOBORO_COOKIE_AUTHENTICATION,
      strategy = AuthenticationStrategy.FirstSuccessful,
    ) {
      install(XoboroCookieCsrfProtection)
      get("/authentication/oauth2") {
        if (!call.nativeUser().isAdmin) {
          call.respondNativeError(
            HttpStatusCode.Forbidden,
            "forbidden",
            "Administrator role is required",
          )
          return@get
        }
        val policy = oauth2.policy()
        call.respond(
          XoboroOAuth2ConfigurationResponse(
            providers =
              oauth2
                .providers()
                .map { XoboroOAuth2ProviderResponse(it.registrationId, it.name) }
                .sortedBy(XoboroOAuth2ProviderResponse::registrationId),
            accountCreationEnabled = policy.accountCreationEnabled,
            oidcEmailVerificationRequired = policy.oidcEmailVerificationRequired,
            accountLinking = policy.accountLinking.name,
          ),
        )
      }
    }
  }
}

@Serializable
data class XoboroOAuth2ProviderResponse(
  val registrationId: String,
  val name: String,
)

@Serializable
data class XoboroOAuth2ConfigurationResponse(
  val providers: List<XoboroOAuth2ProviderResponse>,
  val accountCreationEnabled: Boolean,
  val oidcEmailVerificationRequired: Boolean,
  val accountLinking: String,
)
