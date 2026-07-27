package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.komgaOpenApiRoutes() {
  get("/v3/api-docs") {
    call.respondText(
      text = KomgaOpenApiDocument.json,
      contentType = ContentType.Application.Json,
    )
  }
}

internal object KomgaOpenApiDocument {
  val json: String by lazy {
    requireNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
      "Bundled Komga OpenAPI contract is missing"
    }.bufferedReader(Charsets.UTF_8)
      .use { it.readText() }
  }

  private const val RESOURCE_PATH = "/contracts/komga-1.25.0-openapi.json"
}
