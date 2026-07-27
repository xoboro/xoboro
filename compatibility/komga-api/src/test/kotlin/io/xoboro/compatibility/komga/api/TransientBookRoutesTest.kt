package io.xoboro.compatibility.komga.api

import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.TransientBook
import io.xoboro.core.application.TransientBookLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TransientBookRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `protects transient scan analysis and page delivery`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("transient-routes.sqlite"))).use {
        database ->
      var sequence = 0
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "user-${++sequence}" },
          currentTimeMillis = { 10 },
        )
      testApplication {
        application {
          install(ContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaTransientBookRoutes(SyntheticTransientBooks())
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
          client.post("/api/v1/transient-books") {
            basicAuth(USER_EMAIL, USER_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"path":"/synthetic"}""")
          }.status,
        )
        val scanned =
          client.post("/api/v1/transient-books") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"path":"/synthetic"}""")
          }
        assertEquals(HttpStatusCode.OK, scanned.status)
        assertTrue(scanned.bodyAsText().contains("\"status\":\"UNKNOWN\""))
        val analyzed =
          client.post("/api/v1/transient-books/transient-1/analyze") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, analyzed.status)
        assertTrue(analyzed.bodyAsText().contains("\"status\":\"READY\""))
        val page =
          client.get("/api/v1/transient-books/transient-1/pages/1") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("image/png", page.headers[HttpHeaders.ContentType])
        assertEquals("synthetic-page", page.bodyAsText())
      }
    }
  }

  private class SyntheticTransientBooks : TransientBookLifecycle {
    private val plain =
      TransientBook(
        id = "transient-1",
        path = "file:///synthetic/synthetic.cbz",
        name = "synthetic.cbz",
        sizeBytes = 14,
        fileLastModifiedMillis = 20,
      )

    override fun scan(path: String): List<TransientBook> = listOf(plain)

    override fun findByIdOrNull(id: String): TransientBook? =
      analyze(id)

    override fun analyze(id: String): TransientBook? =
      plain.takeIf { it.id == id }?.copy(
        media =
          BookMedia(
            bookId = BookId(id),
            status = MediaStatus.READY,
            mediaType = "application/zip",
            profile = MediaProfile.DIVINA,
            pages =
              listOf(
                BookPage(
                  number = 1,
                  fileName = "001.png",
                  mediaType = "image/png",
                  fileSize = 14,
                ),
              ),
            createdAtMillis = 20,
          ),
      )

    override fun openPage(
      id: String,
      pageNumber: Int,
    ): MediaContentStream? =
      if (id == plain.id && pageNumber == 1) {
        ByteArrayContent("synthetic-page".encodeToByteArray())
      } else {
        null
      }
  }

  private class ByteArrayContent(
    private val bytes: ByteArray,
  ) : MediaContentStream {
    private var position = 0
    override val mediaType: String = "image/png"
    override val contentLength: Long = bytes.size.toLong()

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (position >= bytes.size) return -1
      val count = minOf(length, bytes.size - position)
      bytes.copyInto(buffer, offset, position, position + count)
      position += count
      return count
    }

    override fun close() = Unit
  }

  private companion object {
    const val ADMIN_EMAIL = "admin@example.invalid"
    const val ADMIN_PASSWORD = "synthetic-admin-password"
    const val USER_EMAIL = "reader@example.invalid"
    const val USER_PASSWORD = "synthetic-reader-password"
  }
}
