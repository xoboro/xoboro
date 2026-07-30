package io.xoboro.server

import io.ktor.http.ContentType
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.xoboro.server.api.XOBORO_API_PREFIX

/**
 * Publishes the native API's OpenAPI description and a browser view of it.
 *
 * Both are unauthenticated on purpose. The description contains no catalog data and no
 * configuration - only paths, methods, parameters and status codes, all of which a client has to
 * know before it can authenticate at all. Requiring a session to discover how to create one would
 * be circular.
 *
 * `openapi.yaml` is served verbatim from the committed resource rather than generated at runtime,
 * so what a client reads is exactly what is reviewed in the repository. Parity between that file
 * and the routes this server actually registers is enforced by
 * `XoboroNativeOpenApiContractTest`, not by convention.
 */
fun Route.xoboroOpenApiRoutes() {
  get("$XOBORO_API_PREFIX/openapi.yaml") {
    val description =
      requireNotNull(
        this::class.java.classLoader.getResourceAsStream(OPEN_API_RESOURCE),
      ) { "$OPEN_API_RESOURCE is missing from the packaged resources" }
        .use { it.readBytes().decodeToString() }
    call.respondText(description, YAML_CONTENT_TYPE)
  }
  swaggerUI(path = "$XOBORO_API_PREFIX/docs", swaggerFile = OPEN_API_RESOURCE)
}

private const val OPEN_API_RESOURCE = "openapi/xoboro-native-v1.yaml"

private val YAML_CONTENT_TYPE = ContentType("application", "yaml")
