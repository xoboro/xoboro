package io.xoboro.compatibility.komga.api

import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.BCryptPasswordHasher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class PageHashCacheRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `revalidates duplicate page thumbnails`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("page-hash-cache.sqlite"))).use {
        database ->
      val users =
        UserLifecycle(
          users = JooqUserRepository(database),
          passwordHasher = BCryptPasswordHasher(),
          userIdFactory = { "admin-1" },
          currentTimeMillis = { 10 },
        )
      val hashes = SyntheticPageHashes()
      testApplication {
        application {
          install(ContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users)
          routing {
            komgaClaimRoutes(users)
            komgaPageHashRoutes(
              hashes = hashes,
              lifecycle = PageHashLifecycle(hashes) { 20 },
              content = SyntheticPageContent(),
            )
          }
        }
        client.claimAdministrator()
        val initial =
          client.get("/api/v1/page-hashes/unknown/synthetic-hash/thumbnail?resize=300") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
          }
        assertEquals(HttpStatusCode.OK, initial.status)
        assertEquals("image/jpeg", initial.headers[HttpHeaders.ContentType])
        assertEquals(KOMGA_PRIVATE_REVALIDATE, initial.headers[HttpHeaders.CacheControl])
        val entityTag = requireNotNull(initial.headers[HttpHeaders.ETag])
        val unchanged =
          client.get("/api/v1/page-hashes/unknown/synthetic-hash/thumbnail?resize=300") {
            basicAuth(ADMIN_EMAIL, ADMIN_PASSWORD)
            header(HttpHeaders.IfNoneMatch, "W/$entityTag")
          }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
        assertEquals(entityTag, unchanged.headers[HttpHeaders.ETag])
      }
    }
  }

  private suspend fun io.ktor.client.HttpClient.claimAdministrator() {
    assertEquals(
      HttpStatusCode.OK,
      post("/api/v1/claim") {
        header("X-Komga-Email", ADMIN_EMAIL)
        header("X-Komga-Password", ADMIN_PASSWORD)
      }.status,
    )
  }

  private class SyntheticPageHashes : PageHashRepository {
    private val match =
      PageHashMatch(
        mediaItemId = BookId("book-1"),
        sourceItemId = "source-1",
        pageNumber = 1,
        fileName = "001.jpg",
        fileSize = PAGE_BYTES.size.toLong(),
        mediaType = "image/jpeg",
      )

    override fun findKnownOrNull(hash: String): KnownPageHash? = null

    override fun findKnown(
      actions: Set<PageHashAction>,
      page: CatalogPageRequest,
    ): CatalogPage<KnownPageHash> = emptyPage(page)

    override fun findUnknown(page: CatalogPageRequest): CatalogPage<UnknownPageHash> =
      CatalogPage(
        content = listOf(UnknownPageHash("synthetic-hash", PAGE_BYTES.size.toLong(), 2)),
        page = page.page,
        size = page.size,
        totalElements = 1,
      )

    override fun findMatches(
      hash: String,
      page: CatalogPageRequest,
    ): CatalogPage<PageHashMatch> =
      CatalogPage(
        content = listOf(match).takeIf { hash == "synthetic-hash" }.orEmpty(),
        page = page.page,
        size = page.size,
        totalElements = if (hash == "synthetic-hash") 1 else 0,
      )

    override fun upsert(known: KnownPageHash) = Unit

    private fun <T> emptyPage(page: CatalogPageRequest): CatalogPage<T> =
      CatalogPage(emptyList(), page.page, page.size, 0)
  }

  private class SyntheticPageContent : BookContentAccess {
    override fun pages(bookId: BookId): List<BookPage>? = null

    override fun openPage(
      bookId: BookId,
      pageNumber: Int,
      request: PageImageRequest,
    ): MediaContentStream? =
      ByteArrayMediaContent(PAGE_BYTES).takeIf {
        bookId == BookId("book-1") && pageNumber == 1
      }

    override fun openBook(bookId: BookId): MediaContentStream? = null
  }

  private class ByteArrayMediaContent(
    private val bytes: ByteArray,
  ) : MediaContentStream {
    private var position = 0
    override val mediaType: String = "image/jpeg"
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
    val PAGE_BYTES = "synthetic-thumbnail".encodeToByteArray()
  }
}
