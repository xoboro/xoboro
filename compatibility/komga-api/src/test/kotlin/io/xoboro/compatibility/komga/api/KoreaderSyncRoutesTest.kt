package io.xoboro.compatibility.komga.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.Book
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaKind
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.Series
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SeriesMetadata
import io.xoboro.core.domain.SourceLocation
import io.xoboro.core.domain.UserRole
import io.xoboro.server.persistence.DatabaseConfig
import io.xoboro.server.persistence.JooqApiKeyRepository
import io.xoboro.server.persistence.JooqBookMediaRepository
import io.xoboro.server.persistence.JooqBookRepository
import io.xoboro.server.persistence.JooqCatalogReadRepository
import io.xoboro.server.persistence.JooqLibraryRepository
import io.xoboro.server.persistence.JooqMediaItemFingerprintIndex
import io.xoboro.server.persistence.JooqReadProgressRepository
import io.xoboro.server.persistence.JooqSeriesMetadataRepository
import io.xoboro.server.persistence.JooqSeriesRepository
import io.xoboro.server.persistence.JooqUserRepository
import io.xoboro.server.persistence.XoboroDatabase
import io.xoboro.server.security.AdaptivePasswordHasher
import io.xoboro.server.security.Sha512TokenEncoder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class KoreaderSyncRoutesTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `authenticates with KOReader header and synchronizes EPUB progress`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("koreader.sqlite"))).use { database ->
      seed(database)
      var now = 10L
      val userIds = ArrayDeque(listOf("user-1", "user-2"))
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = userIds::removeFirst,
          currentTimeMillis = { now++ },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      val apiKeys =
        ApiKeyLifecycle(
          users = userRepository,
          apiKeys = JooqApiKeyRepository(database),
          tokenEncoder = Sha512TokenEncoder(),
          apiKeyIdFactory = { "key-1" },
          plainTextKeyFactory = { TOKEN },
          currentTimeMillis = { now++ },
        )
      apiKeys.create(user.id, "KOReader")
      val limitedUser =
        users.createUser(
          email = "limited@example.invalid",
          rawPassword = "SyntheticPassword2!",
          roles = setOf(UserRole.PAGE_STREAMING),
        )
      ApiKeyLifecycle(
        users = userRepository,
        apiKeys = JooqApiKeyRepository(database),
        tokenEncoder = Sha512TokenEncoder(),
        apiKeyIdFactory = { "key-2" },
        plainTextKeyFactory = { LIMITED_TOKEN },
        currentTimeMillis = { now++ },
      ).create(limitedUser.id, "Limited client")
      val books = JooqBookRepository(database)
      val analyzed = JooqBookMediaRepository(database)
      val progress =
        ReadProgressLifecycle(
          books = books,
          series = JooqSeriesRepository(database),
          media = analyzed,
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { now++ },
        )
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = analyzed,
          progress = progress,
          currentTimeMillis = { now++ },
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users, apiKeys)
          routing {
            komgaKoreaderSyncRoutes(sync)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json()
            }
          }

        assertEquals(
          HttpStatusCode.Forbidden,
          client.post("/koreader/users/create").status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/koreader/users/auth").status,
        )
        assertEquals(
          HttpStatusCode.Forbidden,
          client.get("/koreader/users/auth") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, LIMITED_TOKEN)
          }.status,
        )
        assertEquals(
          "OK",
          client.get("/koreader/users/auth") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.Accept, "application/vnd.koreader.v1+json")
          }.body<KoreaderUserAuthenticationDto>().authorized,
        )
        assertEquals(
          HttpStatusCode.OK,
          client.get("/koreader/syncs/progress/pdf-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.status,
        )

        val update =
          KoreaderDocumentProgressDto(
            document = "epub-fingerprint",
            percentage = 0.75F,
            progress = "/body/DocFragment[2]/body/p[1]/text().0",
            device = "Synthetic device",
            deviceId = "device-1",
          )
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/koreader/syncs/progress") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(update)
          }.status,
        )
        val saved =
          client.get("/koreader/syncs/progress/epub-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.body<KoreaderDocumentProgressDto>()
        assertEquals("epub-fingerprint", saved.document)
        // The last of two positions, so `(2 - 1) / 2`. It read 1 while the analyzer used
        // `position / count`; see ADR 0106.
        assertEquals(0.5F, saved.percentage)
        assertEquals("/body/DocFragment[2].0", saved.progress)
        assertEquals("Synthetic device", saved.device)
        assertEquals("device-1", saved.deviceId)

        assertEquals(
          HttpStatusCode.BadRequest,
          client.put("/koreader/syncs/progress") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(update.copy(progress = "/body/DocFragment[99].0"))
          }.status,
        )
        assertEquals(
          HttpStatusCode.NotFound,
          client.get("/koreader/syncs/progress/missing-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.status,
        )
      }
    }
  }

  /**
   * The round trip ADR 0105 said had no coverage, which is why correcting the locator
   * convention was deferred.
   *
   * A position is synced from a device, read back, and the returned progress string synced
   * again. The stored page has to be the same both times, and it has to be the *right* page at
   * **both** ends: page 1 for the first position and the last page for the last position. The
   * ends are the whole point. The old `position / count` convention and the old
   * `round(pageCount * totalProgression)` mapping cancelled each other exactly at the last
   * position, so a test that only checked somewhere in the middle would have passed before this
   * change and after it, and would have passed with the analyzer corrected and the mapping left
   * alone — the regression this had to avoid.
   */
  @Test
  fun `round trips the first and last position to the same stored page`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("round-trip.sqlite"))).use { database ->
      seed(database, coarse = true)
      var now = 100L
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { now++ },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      val media = JooqBookMediaRepository(database)
      val progresses = JooqReadProgressRepository(database)
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = media,
          progress =
            ReadProgressLifecycle(
              books = JooqBookRepository(database),
              series = JooqSeriesRepository(database),
              media = media,
              progresses = progresses,
              currentTimeMillis = { now++ },
            ),
          currentTimeMillis = { now++ },
        )

      // Writes `progress` as a device would, then reads back what the next GET would answer
      // and the page that landed in durable read progress.
      fun syncAndRead(
        fingerprint: String,
        bookId: String,
        progress: String,
      ): Pair<Int, KoreaderDocumentProgressDto> {
        sync.update(
          KoreaderDocumentProgressDto(
            document = fingerprint,
            // Ignored for an EPUB: the position comes from the DocFragment index. Sent as a
            // value that is wrong for every position so that a mapping which used it instead
            // of the index could not accidentally agree with the expectations below.
            percentage = 0.99F,
            progress = progress,
            device = "Synthetic device",
            deviceId = "device-1",
          ),
          user,
        )
        val stored =
          requireNotNull(
            progresses.findByBookIdAndUserIdOrNull(BookId(bookId), user.id),
          ) { "The sync did not write read progress" }
        val found = sync.find(fingerprint, user)
        return stored.page to (found as KoreaderProgressResult.Found).progress
      }

      // epub-1: two positions, two pages.
      val (firstPage, firstReply) = syncAndRead("epub-fingerprint", "epub-1", DOC_FRAGMENT_ONE)
      // The first position is page 1, and its percentage is 0 - the start of the publication.
      // Under the old convention this position reported 0.5 and stored page 1 by rounding.
      assertEquals(1, firstPage)
      assertEquals(0F, firstReply.percentage)
      assertEquals("/body/DocFragment[1].0", firstReply.progress)
      // The device sends back exactly what it was given. The page must not move.
      assertEquals(1, syncAndRead("epub-fingerprint", "epub-1", firstReply.progress).first)

      val (lastPage, lastReply) = syncAndRead("epub-fingerprint", "epub-1", DOC_FRAGMENT_TWO)
      // pageCount is 2, so the last position has to reach page 2. This is the assertion the
      // deferral in ADR 0105 was about: correcting the analyzer and leaving the page mapping
      // inverting `totalProgression` gives `round(2 * 0.5) = 1`, and a KOReader user could
      // never reach the last page again.
      assertEquals(2, lastPage)
      assertEquals(0.5F, lastReply.percentage)
      assertEquals("/body/DocFragment[2].0", lastReply.progress)
      assertEquals(2, syncAndRead("epub-fingerprint", "epub-1", lastReply.progress).first)

      // epub-coarse: four positions, two pages, so positions bucket into pages rather than
      // matching them off one to one. Both ends still have to land on the real ends.
      val (coarseFirstPage, coarseFirstReply) =
        syncAndRead("epub-coarse-fingerprint", "epub-coarse", DOC_FRAGMENT_ONE)
      assertEquals(1, coarseFirstPage)
      assertEquals(0F, coarseFirstReply.percentage)
      assertEquals(
        1,
        syncAndRead("epub-coarse-fingerprint", "epub-coarse", coarseFirstReply.progress).first,
      )

      val (coarseLastPage, coarseLastReply) =
        syncAndRead("epub-coarse-fingerprint", "epub-coarse", DOC_FRAGMENT_TWO)
      // Position 3 of 4 - the start of the second resource - buckets into page
      // `floor(2 * 2 / 4) + 1`, the last page. Its percentage is `(3 - 1) / 4`, which this
      // fixture cannot tell apart from a per-resource ratio because `2 / 4 == 1 / 2`; the
      // page assertions are what discriminate here, and `epub-1` above pins the percentage
      // against a position count that differs from the resource count only in the coarse case.
      assertEquals(2, coarseLastPage)
      assertEquals(0.5F, coarseLastReply.percentage)
      assertEquals(
        2,
        syncAndRead("epub-coarse-fingerprint", "epub-coarse", coarseLastReply.progress).first,
      )
    }
  }

  /**
   * Progress written without a locator still reports the page's *start*.
   *
   * The Komga REST `read-progress` patch records a page and no locator, so the KOReader GET has
   * to derive a percentage from the page alone. That branch has to agree with the locator branch
   * beside it or the one field carries two conventions, which is the failure ADR 0106 exists to
   * remove. Nothing else reaches it: every other test here writes through the KOReader PUT, which
   * always stores a locator.
   */
  @Test
  fun `derives the KOReader percentage from a page when no locator was stored`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("no-locator.sqlite"))).use { database ->
      seed(database)
      var now = 100L
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { now++ },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      val media = JooqBookMediaRepository(database)
      val progress =
        ReadProgressLifecycle(
          books = JooqBookRepository(database),
          series = JooqSeriesRepository(database),
          media = media,
          progresses = JooqReadProgressRepository(database),
          currentTimeMillis = { now++ },
        )
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = media,
          progress = progress,
          currentTimeMillis = { now++ },
        )

      fun percentageAtPage(page: Int): Float {
        val stored =
          requireNotNull(progress.updateBook(BookId("epub-1"), user.id, page = page, completed = null))
        require(stored.locatorJson == null) {
          "This test only means something while the page-only update stores no locator"
        }
        return (sync.find("epub-fingerprint", user) as KoreaderProgressResult.Found)
          .progress
          .percentage
      }

      // Two pages. Page 1 starts the publication, so 0 - not the `1 / 2` the previous
      // arithmetic reported. Page 2 starts halfway.
      assertEquals(0F, percentageAtPage(1))
      assertEquals(0.5F, percentageAtPage(2))
    }
  }

  @Test
  fun `reports conflict for duplicate fingerprints`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("duplicates.sqlite"))).use { database ->
      seed(database, duplicate = true)
      val books = JooqBookRepository(database)
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = JooqBookMediaRepository(database),
          progress =
            ReadProgressLifecycle(
              books = books,
              series = JooqSeriesRepository(database),
              media = JooqBookMediaRepository(database),
              progresses = JooqReadProgressRepository(database),
              currentTimeMillis = { 20 },
            ),
          currentTimeMillis = { 20 },
        )
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 10 },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")

      assertEquals(
        KoreaderProgressResult.Conflict,
        sync.find("epub-fingerprint", user),
      )
    }
  }

  @Test
  fun `accepts repeated progress updates at the same timestamp`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("repeated-progress.sqlite"))).use { database ->
      seed(database)
      val userRepository = JooqUserRepository(database)
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = { "user-1" },
          currentTimeMillis = { 10 },
        )
      val user = users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      val apiKeys =
        ApiKeyLifecycle(
          users = userRepository,
          apiKeys = JooqApiKeyRepository(database),
          tokenEncoder = Sha512TokenEncoder(),
          apiKeyIdFactory = { "key-1" },
          plainTextKeyFactory = { TOKEN },
          currentTimeMillis = { 20 },
        )
      apiKeys.create(user.id, "KOReader")
      val books = JooqBookRepository(database)
      val media = JooqBookMediaRepository(database)
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = media,
          progress =
            ReadProgressLifecycle(
              books = books,
              series = JooqSeriesRepository(database),
              media = media,
              progresses = JooqReadProgressRepository(database),
              currentTimeMillis = { 20 },
            ),
          currentTimeMillis = { 20 },
        )

      testApplication {
        application {
          install(ServerContentNegotiation) {
            json()
          }
          installKomgaBasicAuthentication(users, apiKeys)
          routing {
            komgaKoreaderSyncRoutes(sync)
          }
        }
        val client =
          createClient {
            install(ContentNegotiation) {
              json()
            }
          }
        val update =
          KoreaderDocumentProgressDto(
            document = "epub-fingerprint",
            percentage = 0.75F,
            progress = "/body/DocFragment[2]/body/p[1]/text().0",
            device = "Synthetic device",
            deviceId = "device-1",
          )

        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/koreader/syncs/progress") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(update)
          }.status,
        )
        // The fixed clock makes the resend carry an identical timestamp. Vary the device so a
        // rejected write is distinguishable from an accepted write of identical values.
        assertEquals(
          HttpStatusCode.NoContent,
          client.put("/koreader/syncs/progress") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(update.copy(device = "Other device", deviceId = "device-2"))
          }.status,
        )
        val saved =
          client.get("/koreader/syncs/progress/epub-fingerprint") {
            header(KOMGA_KOREADER_AUTHENTICATION_HEADER, TOKEN)
          }.body<KoreaderDocumentProgressDto>()
        assertEquals("Synthetic device", saved.device)
        assertEquals("device-1", saved.deviceId)
      }
    }
  }

  @Test
  fun `hides age-restricted media items from fingerprint lookups`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("restricted.sqlite"))).use { database ->
      seed(database)
      // The one series in the fixture is rated 18; the restricted reader may see up to 12.
      JooqSeriesMetadataRepository(database).upsert(
        SeriesMetadata(
          seriesId = SeriesId("series-1"),
          title = "Synthetic series",
          ageRating = 18,
          createdAtMillis = 1,
        ),
      )
      val userRepository = JooqUserRepository(database)
      var now = 10L
      val userIds = ArrayDeque(listOf("user-1", "user-2"))
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = userIds::removeFirst,
          currentTimeMillis = { now++ },
        )
      val administrator =
        users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      // Shares every library, so only the age restriction can deny this reader.
      val restricted =
        users.createUser(
          email = "restricted@example.invalid",
          rawPassword = "SyntheticPassword2!",
          roles = setOf(UserRole.PAGE_STREAMING),
          restrictions =
            ContentRestrictions(
              ageRestriction = AgeRestriction(age = 12, mode = RestrictionMode.ALLOW_ONLY),
            ),
        )
      val media = JooqBookMediaRepository(database)
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = media,
          progress =
            ReadProgressLifecycle(
              books = JooqBookRepository(database),
              series = JooqSeriesRepository(database),
              media = media,
              progresses = JooqReadProgressRepository(database),
              currentTimeMillis = { now++ },
            ),
          currentTimeMillis = { now++ },
        )
      val update =
        KoreaderDocumentProgressDto(
          document = "epub-fingerprint",
          percentage = 0.75F,
          progress = "/body/DocFragment[2]/body/p[1]/text().0",
          device = "Synthetic device",
          deviceId = "device-1",
        )

      // Control: the fingerprint really does resolve, so a denial below cannot come from a
      // fixture that simply has no matching item.
      assertEquals(KoreaderProgressResult.NoProgress, sync.find("epub-fingerprint", administrator))

      assertEquals(KoreaderProgressResult.NotFound, sync.find("epub-fingerprint", restricted))
      assertFailsWith<IllegalStateException> { sync.update(update, restricted) }

      // The rejected write must not have landed under the administrator's item either.
      assertEquals(KoreaderProgressResult.NoProgress, sync.find("epub-fingerprint", administrator))
    }
  }

  @Test
  fun `hides media items in unshared libraries from fingerprint lookups`() {
    XoboroDatabase.open(DatabaseConfig(tempDirectory.resolve("unshared.sqlite"))).use { database ->
      seed(database)
      val userRepository = JooqUserRepository(database)
      var now = 10L
      val userIds = ArrayDeque(listOf("user-1", "user-2"))
      val users =
        UserLifecycle(
          users = userRepository,
          passwordHasher = AdaptivePasswordHasher(),
          userIdFactory = userIds::removeFirst,
          currentTimeMillis = { now++ },
        )
      users.claimInitialAdministrator("reader@example.invalid", "SyntheticPassword1!")
      val outsider =
        users.createUser(
          email = "outsider@example.invalid",
          rawPassword = "SyntheticPassword2!",
          roles = setOf(UserRole.PAGE_STREAMING),
          sharedLibraryIds = emptySet(),
          sharesAllLibraries = false,
        )
      val media = JooqBookMediaRepository(database)
      val sync =
        KoreaderSyncLifecycle(
          fingerprints = JooqMediaItemFingerprintIndex(database),
          catalog = JooqCatalogReadRepository(database),
          media = media,
          progress =
            ReadProgressLifecycle(
              books = JooqBookRepository(database),
              series = JooqSeriesRepository(database),
              media = media,
              progresses = JooqReadProgressRepository(database),
              currentTimeMillis = { now++ },
            ),
          currentTimeMillis = { now++ },
        )

      assertEquals(KoreaderProgressResult.NotFound, sync.find("epub-fingerprint", outsider))
    }
  }

  private fun seed(
    database: XoboroDatabase,
    duplicate: Boolean = false,
    coarse: Boolean = false,
  ) {
    val libraryId = LibraryId("library-1")
    val seriesId = SeriesId("series-1")
    JooqLibraryRepository(database).insert(
      Library(
        id = libraryId,
        name = "Synthetic library",
        root = SourceLocation("local", "root"),
        createdAtMillis = 1,
      ),
    )
    JooqSeriesRepository(database).insert(
      Series(
        id = seriesId,
        libraryId = libraryId,
        name = "Synthetic series",
        relativePath = "series",
        sourceItemId = "series",
        fileModifiedAtMillis = 1,
        createdAtMillis = 1,
      ),
    )
    val books = JooqBookRepository(database)
    val media = JooqBookMediaRepository(database)
    fun insertBook(
      id: String,
      kind: MediaKind,
      fingerprint: String,
    ) {
      books.insert(
        Book(
          id = BookId(id),
          libraryId = libraryId,
          seriesId = seriesId,
          name = id,
          relativePath = "series/$id",
          sourceItemId = "series/$id",
          mediaKind = kind,
          fileModifiedAtMillis = 1,
          fileHashKoreader = fingerprint,
          createdAtMillis = 1,
        ),
      )
    }
    insertBook("epub-1", MediaKind.EPUB, "epub-fingerprint")
    insertBook("pdf-1", MediaKind.PDF, "pdf-fingerprint")
    if (duplicate) insertBook("epub-2", MediaKind.EPUB, "epub-fingerprint")
    if (coarse) insertBook("epub-coarse", MediaKind.EPUB, "epub-coarse-fingerprint")

    media.upsert(
      BookMedia(
        bookId = BookId("epub-1"),
        status = MediaStatus.READY,
        mediaType = "application/epub+zip",
        profile = MediaProfile.EPUB,
        pageCount = 2,
        files =
          listOf(
            MediaFile("chapter-1.xhtml", "application/xhtml+xml", kind = MediaFileKind.EPUB_PAGE),
            MediaFile("chapter-2.xhtml", "application/xhtml+xml", kind = MediaFileKind.EPUB_PAGE),
          ),
        // `(position - 1) / count`, the Readium convention: 0 then 0.5 for two positions.
        positions =
          listOf(
            MediaPosition("chapter-1.xhtml", "application/xhtml+xml", 0F, 1, 0F),
            MediaPosition("chapter-2.xhtml", "application/xhtml+xml", 0F, 2, 0.5F),
          ),
        createdAtMillis = 1,
      ),
    )
    media.upsert(
      BookMedia(
        bookId = BookId("pdf-1"),
        status = MediaStatus.READY,
        mediaType = "application/pdf",
        profile = MediaProfile.PDF,
        pageCount = 2,
        createdAtMillis = 1,
      ),
    )
    if (!coarse) return
    // Four positions over two resources against two pages, so `pageCount < positions.size`.
    // That is the ordinary shape for a reflowable EPUB - page count sums compressed entry
    // sizes while positions chunk on uncompressed ones - and it is the case where the page
    // mapping has to bucket several positions into one page instead of matching them off
    // one to one. `epub-1` above, with as many pages as positions, cannot show that.
    media.upsert(
      BookMedia(
        bookId = BookId("epub-coarse"),
        status = MediaStatus.READY,
        mediaType = "application/epub+zip",
        profile = MediaProfile.EPUB,
        pageCount = 2,
        files =
          listOf(
            MediaFile("coarse-1.xhtml", "application/xhtml+xml", kind = MediaFileKind.EPUB_PAGE),
            MediaFile("coarse-2.xhtml", "application/xhtml+xml", kind = MediaFileKind.EPUB_PAGE),
          ),
        positions =
          listOf(
            MediaPosition("coarse-1.xhtml", "application/xhtml+xml", 0F, 1, 0F),
            MediaPosition("coarse-1.xhtml", "application/xhtml+xml", 0.5F, 2, 0.25F),
            MediaPosition("coarse-2.xhtml", "application/xhtml+xml", 0F, 3, 0.5F),
            MediaPosition("coarse-2.xhtml", "application/xhtml+xml", 0.5F, 4, 0.75F),
          ),
        createdAtMillis = 1,
      ),
    )
  }

  private companion object {
    const val TOKEN: String = "0123456789abcdef0123456789abcdef"
    const val LIMITED_TOKEN: String = "fedcba9876543210fedcba9876543210"

    // KOReader's XPointer form. The bracketed index is one-based over the spine's resources.
    const val DOC_FRAGMENT_ONE: String = "/body/DocFragment[1]/body/p[1]/text().0"
    const val DOC_FRAGMENT_TWO: String = "/body/DocFragment[2]/body/p[1]/text().0"
  }
}
