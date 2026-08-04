package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.DuplicatePageRemovalRequester
import io.xoboro.core.application.PageHashLifecycle
import io.xoboro.core.application.PageHashRepository
import io.xoboro.core.application.PasswordHasher
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.KnownPageHash
import io.xoboro.core.domain.MediaItemId
import io.xoboro.core.domain.PageHashAction
import io.xoboro.core.domain.PageHashMatch
import io.xoboro.core.domain.UnknownPageHash
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import io.xoboro.server.security.Sha512TokenEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class XoboroNativeDuplicatePageTest {
  @Test
  fun `lists candidate duplicate pages to an administrator`() =
    testApplication {
      val fixture = install()

      val response =
        client.get("$XOBORO_API_PREFIX/duplicate-pages") {
          bearerAuth(fixture.administratorToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroPageResponse<XoboroDuplicatePageResponse>>()
      assertEquals(listOf("hash-a", "hash-b"), body.items.map { it.hash })
      assertEquals(2, body.totalItems.toInt())
      assertEquals(1, body.totalPages)
      assertEquals(false, body.hasNext)
    }

  @Test
  fun `filters decisions by action and treats no filter as every action`() =
    testApplication {
      val fixture = install()

      val filtered =
        client
          .get("$XOBORO_API_PREFIX/duplicate-pages/decided?action=IGNORE") {
            bearerAuth(fixture.administratorToken)
          }.body<XoboroPageResponse<XoboroDecidedDuplicatePageResponse>>()
      assertEquals(setOf(PageHashAction.IGNORE), fixture.hashes.lastKnownActions)
      assertEquals(listOf("hash-known"), filtered.items.map { it.hash })

      client
        .get("$XOBORO_API_PREFIX/duplicate-pages/decided") {
          bearerAuth(fixture.administratorToken)
        }.body<XoboroPageResponse<XoboroDecidedDuplicatePageResponse>>()

      // A caller that just wants "what have I decided" should not have to enumerate the enum to ask.
      assertEquals(PageHashAction.entries.toSet(), fixture.hashes.lastKnownActions)
    }

  @Test
  fun `rejects an unknown action`() =
    testApplication {
      val fixture = install()

      assertEquals(
        HttpStatusCode.BadRequest,
        client
          .get("$XOBORO_API_PREFIX/duplicate-pages/decided?action=DELETE_EVERYTHING") {
            bearerAuth(fixture.administratorToken)
          }.status,
      )
    }

  @Test
  fun `lists the media items carrying a hash`() =
    testApplication {
      val fixture = install()

      val body =
        client
          .get("$XOBORO_API_PREFIX/duplicate-pages/hash-a/media-items") {
            bearerAuth(fixture.administratorToken)
          }.body<XoboroPageResponse<XoboroDuplicatePageMatchResponse>>()

      assertEquals("hash-a", fixture.hashes.lastMatchedHash)
      assertEquals(listOf("media-1"), body.items.map { it.mediaItemId })
      assertEquals(listOf(7), body.items.map { it.pageNumber })
    }

  @Test
  fun `records a decision and reports a delete count of zero`() =
    testApplication {
      val fixture = install()

      val response =
        client.put("$XOBORO_API_PREFIX/duplicate-pages/hash-a") {
          bearerAuth(fixture.administratorToken)
          contentType(ContentType.Application.Json)
          setBody(XoboroDuplicatePageDecisionRequest(action = "DELETE_MANUAL", sizeBytes = 1_024))
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.body<XoboroDecidedDuplicatePageResponse>()
      assertEquals("DELETE_MANUAL", body.action)
      // Nothing performs removal, so this is the stored value and not a placeholder that will change
      // shape later.
      assertEquals(0, body.deleteCount)
      assertEquals(PageHashAction.DELETE_MANUAL, fixture.hashes.stored?.action)
      assertEquals(1_024L, fixture.hashes.stored?.size)
    }

  @Test
  fun `rejects an unknown decision action`() =
    testApplication {
      val fixture = install()

      assertEquals(
        HttpStatusCode.BadRequest,
        client
          .put("$XOBORO_API_PREFIX/duplicate-pages/hash-a") {
            bearerAuth(fixture.administratorToken)
            contentType(ContentType.Application.Json)
            setBody(XoboroDuplicatePageDecisionRequest(action = "PURGE"))
          }.status,
      )
    }

  @Test
  fun `refuses a non-administrator on every route`() =
    testApplication {
      val fixture = install()

      listOf(
        "/duplicate-pages",
        "/duplicate-pages/decided",
        "/duplicate-pages/hash-a/media-items",
      ).forEach { path ->
        val response =
          client.get("$XOBORO_API_PREFIX$path") { bearerAuth(fixture.readerToken) }
        assertEquals(HttpStatusCode.Forbidden, response.status, "GET $path")
        assertEquals("duplicate_pages_forbidden", response.body<XoboroApiError>().code)
      }

      // Duplicate pages expose file names and sizes across every library, so a reader with a grant on
      // one library must not learn another's file layout from this surface.
      val decided =
        client.put("$XOBORO_API_PREFIX/duplicate-pages/hash-a") {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody(XoboroDuplicatePageDecisionRequest(action = "IGNORE"))
        }
      assertEquals(HttpStatusCode.Forbidden, decided.status)
      assertEquals(null, fixture.hashes.stored)

      // The removal route is a mutation with the same disclosure risk, and must be refused before
      // any decision is looked up or anything is queued.
      fixture.hashes.knownHashes["hash-delete"] =
        KnownPageHash(hash = "hash-delete", action = PageHashAction.DELETE_MANUAL, createdAtMillis = 1)
      val removal =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-delete/removals") {
          bearerAuth(fixture.readerToken)
        }
      assertEquals(HttpStatusCode.Forbidden, removal.status)
      assertEquals("duplicate_pages_forbidden", removal.body<XoboroApiError>().code)
      assertEquals(null, fixture.removals.lastHash)
    }

  @Test
  fun `refuses to execute removal for a hash with no recorded decision`() =
    testApplication {
      val fixture = install()

      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-undecided/removals") {
          bearerAuth(fixture.administratorToken)
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals(
        "duplicate_page_not_marked_for_deletion",
        response.body<XoboroApiError>().code,
      )
      assertEquals(null, fixture.removals.lastHash)
    }

  @Test
  fun `refuses to execute removal for a hash marked IGNORE`() =
    testApplication {
      val fixture = install()
      fixture.hashes.knownHashes["hash-ignored"] =
        KnownPageHash(hash = "hash-ignored", action = PageHashAction.IGNORE, createdAtMillis = 1)

      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-ignored/removals") {
          bearerAuth(fixture.administratorToken)
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals(
        "duplicate_page_not_marked_for_deletion",
        response.body<XoboroApiError>().code,
      )
      assertEquals(null, fixture.removals.lastHash)
    }

  @Test
  fun `executes removal for every match of a hash marked DELETE_MANUAL`() =
    testApplication {
      val fixture = install()
      fixture.hashes.knownHashes["hash-delete"] =
        KnownPageHash(hash = "hash-delete", action = PageHashAction.DELETE_MANUAL, createdAtMillis = 1)
      fixture.hashes.matchesByHash["hash-delete"] =
        listOf(
          syntheticMatch(mediaItemId = "media-1", pageNumber = 3),
          syntheticMatch(mediaItemId = "media-2", pageNumber = 9),
        )

      // Every match is asked for by sending a null list, not by sending nothing: on a route that
      // rewrites archives, the widest possible effect must be something a caller stated.
      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-delete/removals") {
          bearerAuth(fixture.administratorToken)
          contentType(ContentType.Application.Json)
          setBody(XoboroDuplicatePageRemovalRequest(mediaItemIds = null))
        }

      assertEquals(HttpStatusCode.Accepted, response.status)
      assertEquals(
        2,
        response.body<XoboroDuplicatePageRemovalResponse>().queuedMediaItems,
      )
      assertEquals("hash-delete", fixture.removals.lastHash)
      assertEquals(
        setOf("media-1", "media-2"),
        fixture.removals.lastMatches?.map { it.mediaItemId.value }?.toSet(),
      )
    }

  @Test
  fun `refuses a removal request that carries no body rather than removing everything`() =
    testApplication {
      val fixture = install()
      fixture.hashes.knownHashes["hash-delete"] =
        KnownPageHash(hash = "hash-delete", action = PageHashAction.DELETE_MANUAL, createdAtMillis = 1)
      fixture.hashes.matchesByHash["hash-delete"] =
        listOf(
          syntheticMatch(mediaItemId = "media-1", pageNumber = 3),
          syntheticMatch(mediaItemId = "media-2", pageNumber = 9),
        )

      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-delete/removals") {
          bearerAuth(fixture.administratorToken)
        }

      // Which refusal the platform produces is its business; that nothing was queued is not.
      assertEquals(true, response.status.value >= 400, "status was ${response.status}")
      assertEquals(null, fixture.removals.lastMatches)
    }

  @Test
  fun `restricts removal to the requested media items`() =
    testApplication {
      val fixture = install()
      fixture.hashes.knownHashes["hash-delete"] =
        KnownPageHash(hash = "hash-delete", action = PageHashAction.DELETE_AUTO, createdAtMillis = 1)
      fixture.hashes.matchesByHash["hash-delete"] =
        listOf(
          syntheticMatch(mediaItemId = "media-1", pageNumber = 3),
          syntheticMatch(mediaItemId = "media-2", pageNumber = 9),
          syntheticMatch(mediaItemId = "media-3", pageNumber = 12),
        )

      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-delete/removals") {
          bearerAuth(fixture.administratorToken)
          contentType(ContentType.Application.Json)
          setBody(XoboroDuplicatePageRemovalRequest(mediaItemIds = listOf("media-2")))
        }

      assertEquals(HttpStatusCode.Accepted, response.status)
      assertEquals(
        1,
        response.body<XoboroDuplicatePageRemovalResponse>().queuedMediaItems,
      )
      assertEquals(
        listOf("media-2"),
        fixture.removals.lastMatches?.map { it.mediaItemId.value },
      )
    }

  @Test
  fun `honours a media item list sent without a content length`() =
    testApplication {
      val fixture = install()
      fixture.hashes.knownHashes["hash-delete"] =
        KnownPageHash(hash = "hash-delete", action = PageHashAction.DELETE_AUTO, createdAtMillis = 1)
      fixture.hashes.matchesByHash["hash-delete"] =
        listOf(
          syntheticMatch(mediaItemId = "media-1", pageNumber = 3),
          syntheticMatch(mediaItemId = "media-2", pageNumber = 9),
          syntheticMatch(mediaItemId = "media-3", pageNumber = 12),
        )

      // Written to the channel rather than handed over as a value, which is what makes the client
      // send it chunked with no Content-Length. Taking that for an empty body would ignore the list
      // and delete every match instead of the one named - the worst possible way to be wrong on the
      // one route that rewrites the operator's archives.
      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-delete/removals") {
          bearerAuth(fixture.administratorToken)
          contentType(ContentType.Application.Json)
          setBody(
            object : OutgoingContent.WriteChannelContent() {
              override val contentType = ContentType.Application.Json

              override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeStringUtf8("""{"mediaItemIds":["media-2"]}""")
              }
            },
          )
        }

      assertEquals(HttpStatusCode.Accepted, response.status)
      assertEquals(
        listOf("media-2"),
        fixture.removals.lastMatches?.map { it.mediaItemId.value },
      )
    }

  @Test
  fun `rejects a removal request whose media items do not match the hash`() =
    testApplication {
      val fixture = install()
      fixture.hashes.knownHashes["hash-delete"] =
        KnownPageHash(hash = "hash-delete", action = PageHashAction.DELETE_MANUAL, createdAtMillis = 1)
      fixture.hashes.matchesByHash["hash-delete"] =
        listOf(syntheticMatch(mediaItemId = "media-1", pageNumber = 3))

      val response =
        client.post("$XOBORO_API_PREFIX/duplicate-pages/hash-delete/removals") {
          bearerAuth(fixture.administratorToken)
          contentType(ContentType.Application.Json)
          setBody(XoboroDuplicatePageRemovalRequest(mediaItemIds = listOf("media-999")))
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals(
        "duplicate_page_match_not_found",
        response.body<XoboroApiError>().code,
      )
      assertEquals(null, fixture.removals.lastHash)
    }

  private fun syntheticMatch(
    mediaItemId: String,
    pageNumber: Int,
  ): PageHashMatch =
    PageHashMatch(
      mediaItemId = MediaItemId(mediaItemId),
      sourceItemId = "file:///synthetic/series/$mediaItemId.cbz",
      pageNumber = pageNumber,
      fileName = "credits.jpg",
      fileSize = 1_024,
      mediaType = "image/jpeg",
    )

  @Test
  fun `rejects a page size outside the domain bounds`() =
    testApplication {
      val fixture = install()

      assertEquals(
        HttpStatusCode.BadRequest,
        client
          .get("$XOBORO_API_PREFIX/duplicate-pages?size=0") {
            bearerAuth(fixture.administratorToken)
          }.status,
      )
    }

  private fun ApplicationTestBuilder.install(): Fixture {
    val fixture = Fixture()
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        exception<XoboroInvalidQueryException> { call, cause ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", cause.message ?: "Invalid query"),
          )
        }
      }
      routing {
        xoboroNativeDuplicatePageRoutes(fixture.hashes, fixture.lifecycle, fixture.removals)
      }
    }
    createClient {
      install(ClientContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
      }
    }.also { client = it }
    return fixture
  }

  private class Fixture {
    private val repository = InMemoryUserRepository()
    private var tokenSequence = 0
    val hashes = RecordingPageHashRepository()
    val lifecycle = PageHashLifecycle(hashes = hashes, currentTimeMillis = { 1_000 })
    val removals = RecordingDuplicatePageRemovalRequester()
    val sessions =
      UserSessionLifecycle(
        users = repository,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = Sha512TokenEncoder(),
        plainTokenFactory = { "session-${++tokenSequence}" },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    private val users =
      UserLifecycle(
        users = repository,
        passwordHasher =
          object : PasswordHasher {
            override fun hash(rawPassword: String): String = "hash:$rawPassword"

            override fun matches(
              rawPassword: String,
              passwordHash: String,
            ): Boolean = passwordHash == hash(rawPassword)
          },
        userIdFactory = { "user-${repository.count() + 1}" },
        currentTimeMillis = { 1_000 },
      )
    val administratorToken: String =
      users
        .claimInitialAdministrator("admin@example.invalid", "synthetic-password")
        .let { requireNotNull(sessions.create(it)).plainToken }
    val readerToken: String =
      users
        .createUser(
          email = "reader@example.invalid",
          rawPassword = "synthetic-password",
          roles = setOf(UserRole.PAGE_STREAMING),
        ).let { requireNotNull(sessions.create(it)).plainToken }
  }

  private class RecordingPageHashRepository : PageHashRepository {
    var stored: KnownPageHash? = null
      private set
    var lastKnownActions: Set<PageHashAction>? = null
      private set
    var lastMatchedHash: String? = null
      private set

    /**
     * Decisions a test wants [findKnownOrNull] to report for hashes other than [stored], keyed by
     * hash. [stored] alone only models the one hash a `PUT` in the same test just decided on;
     * removal tests need to probe hashes that were decided on before the test even started.
     */
    val knownHashes: MutableMap<String, KnownPageHash> = mutableMapOf()

    /**
     * Matches [findMatches] reports for a hash, keyed by hash and defaulting to empty. `"hash-a"`
     * keeps its original fixed match so the pre-existing media-item listing test is unaffected.
     */
    val matchesByHash: MutableMap<String, List<PageHashMatch>> =
      mutableMapOf(
        "hash-a" to
          listOf(
            PageHashMatch(
              mediaItemId = MediaItemId("media-1"),
              sourceItemId = "file:///synthetic/series/chapter.cbz",
              pageNumber = 7,
              fileName = "credits.jpg",
              fileSize = 1_024,
              mediaType = "image/jpeg",
            ),
          ),
      )

    override fun findKnownOrNull(hash: String): KnownPageHash? =
      stored?.takeIf { it.hash == hash } ?: knownHashes[hash]

    override fun findKnown(
      actions: Set<PageHashAction>,
      page: CatalogPageRequest,
    ): CatalogPage<KnownPageHash> {
      lastKnownActions = actions
      return CatalogPage(
        content =
          listOf(
            KnownPageHash(
              hash = "hash-known",
              size = 512,
              action = PageHashAction.IGNORE,
              createdAtMillis = 1,
            ),
          ),
        page = page.page,
        size = page.size,
        totalElements = 1,
      )
    }

    override fun findUnknown(page: CatalogPageRequest): CatalogPage<UnknownPageHash> =
      CatalogPage(
        content =
          listOf(
            UnknownPageHash(hash = "hash-a", size = 1_024, matchCount = 3),
            UnknownPageHash(hash = "hash-b", size = null, matchCount = 2),
          ),
        page = page.page,
        size = page.size,
        totalElements = 2,
      )

    override fun findMatches(
      hash: String,
      page: CatalogPageRequest,
    ): CatalogPage<PageHashMatch> {
      lastMatchedHash = hash
      val content = matchesByHash[hash].orEmpty()
      return CatalogPage(
        content = content,
        page = page.page,
        size = page.size,
        totalElements = content.size.toLong(),
      )
    }

    override fun upsert(known: KnownPageHash) {
      stored = known
    }
  }

  /**
   * Records what a removal route asked it to queue and reports back a count in the same shape as
   * [io.xoboro.server.tasks.DurableCompatibilityMaintenanceRequester]: the number of distinct media
   * items among the matches it was given, not the raw match count.
   */
  private class RecordingDuplicatePageRemovalRequester : DuplicatePageRemovalRequester {
    var lastHash: String? = null
      private set
    var lastMatches: List<PageHashMatch>? = null
      private set

    override fun deleteDuplicatePages(
      hash: String,
      matches: List<PageHashMatch>,
    ): Int {
      lastHash = hash
      lastMatches = matches
      return matches.map { it.mediaItemId }.distinct().size
    }
  }

  private class InMemoryUserRepository : UserRepository {
    private val users = linkedMapOf<UserId, User>()

    override fun count(): Long = users.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = users[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = users.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      users[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (users.isNotEmpty()) return false
      insert(user)
      return true
    }

    override fun update(user: User) {
      users[user.id] = user
    }

    override fun delete(id: UserId) {
      users.remove(id)
    }
  }
}
