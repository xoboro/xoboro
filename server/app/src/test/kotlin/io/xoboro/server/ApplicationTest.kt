package io.xoboro.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
  @Test
  fun `health endpoint reports an available Xoboro service`() =
    testApplication {
      application {
        xoboroModule()
      }
      val client =
        createClient {
          install(ContentNegotiation) {
            json()
          }
        }

      val response = client.get("/health")

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(HealthResponse(), response.body())
    }

  @Test
  fun `readiness endpoint reflects runtime availability`() =
    testApplication {
      application {
        xoboroModule(readiness = { false })
      }
      val client =
        createClient {
          install(ContentNegotiation) {
            json()
          }
        }

      val response = client.get("/ready")

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertEquals(HealthResponse(status = "DOWN"), response.body())
    }

  @Test
  fun `application stop closes its runtime owner`() {
    var stopped = false

    testApplication {
      application {
        xoboroModule(onStop = { stopped = true })
      }
      startApplication()
    }

    assertEquals(true, stopped)
  }
}
