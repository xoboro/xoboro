package io.xoboro.compatibility.komga.api

import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookImportCommand
import io.xoboro.core.application.CatalogFileLifecycleRequester
import io.xoboro.core.application.SourceCopyMode
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class FileLifecycleRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `protects import and deletion while emitting Komga accepted semantics`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("file-routes.sqlite"))).use {
        database ->
      var sequence = 0
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-${++sequence}" },
          currentTimeMillis = { 10 },
        )
      val requester = RecordingFileRequester()
      testApplication {
        application {
          install(ContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaFileLifecycleRoutes(requester)
          }
        }
        assertEquals(
          HttpStatusCode.OK,
          client.post("/api/v1/claim") {
            header("X-Komga-Email", ADMIN_EMAIL)
            header("X-Komga-Password", ADMIN_PASSWORD)
          }.status,
        )
        users.createUser(
          email = USER_EMAIL,
          rawPassword = USER_PASSWORD,
          roles = emptySet<UserRole>(),
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.delete("/api/v1/books/book-1/file") {
            basicAuth(USER_EMAIL, USER_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.post("/api/v1/books/import") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              """
              {
                "books": [{
                  "sourceFile": "/synthetic/source.cbz",
                  "seriesId": "series-1",
                  "destinationName": "destination.cbz"
                }],
                "copyMode": "COPY"
              }
              """.trimIndent(),
            )
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.post("/api/v1/books/import") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
              """
              {
                "books": [{
                  "sourceFile": "",
                  "seriesId": ""
                }],
                "copyMode": "COPY"
              }
              """.trimIndent(),
            )
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.delete("/api/v1/books/book-1/file") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          HttpStatusCode.Accepted,
          client.delete("/api/v1/series/series-1/file") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }.status,
        )
        assertEquals(
          listOf(
            "import:COPY:/synthetic/source.cbz:series-1:destination.cbz",
            "delete-book:book-1",
            "delete-series:series-1",
          ),
          requester.calls,
        )
      }
    }
  }

  private class RecordingFileRequester : CatalogFileLifecycleRequester {
    val calls = mutableListOf<String>()

    override fun importBooks(
      books: List<BookImportCommand>,
      copyMode: SourceCopyMode,
    ): Int {
      books.forEach { book ->
        calls +=
          "import:$copyMode:${book.sourceFile}:${book.seriesId.value}:${book.destinationName}"
      }
      return books.size
    }

    override fun deleteBook(id: BookId): Boolean {
      calls += "delete-book:${id.value}"
      return true
    }

    override fun deleteSeries(id: SeriesId): Boolean {
      calls += "delete-series:${id.value}"
      return true
    }
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val USER_EMAIL = "reader@example.invalid"
    const val USER_PASSWORD = "synthetic-reader-password"
  }
}
