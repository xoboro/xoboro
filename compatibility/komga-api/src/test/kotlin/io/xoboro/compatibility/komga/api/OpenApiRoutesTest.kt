package io.xoboro.compatibility.komga.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiRoutesTest {
  @Test
  fun `serves the pinned Komga contract without authentication`() =
    testApplication {
      application {
        routing {
          komgaOpenApiRoutes()
        }
      }

      val response = client.get("/v3/api-docs")

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
      val body = response.bodyAsText()
      assertTrue(body.contains(""""title": "Komga API""""))
      assertTrue(body.contains(""""version": "1.25.0""""))
      assertTrue(body.contains(""""/api/v1/books": {"""))
    }
}
