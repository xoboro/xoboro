package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.xoboro.core.application.AuthenticationActivityLifecycle
import io.xoboro.core.application.ClientSettingsLifecycle
import io.xoboro.core.application.ServerSettingStore
import io.xoboro.core.application.ServerSettingsLifecycle
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.AuthenticationActivity
import io.xoboro.core.domain.AuthenticationActivityPage
import io.xoboro.core.domain.AuthenticationActivityPageRequest
import io.xoboro.core.domain.AuthenticationActivityRepository
import io.xoboro.core.domain.AuthenticationActivitySortField
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.ClientSetting
import io.xoboro.core.domain.ClientSettingsRepository
import io.xoboro.core.domain.HistoricalEvent
import io.xoboro.core.domain.HistoricalEventPage
import io.xoboro.core.domain.HistoricalEventPageRequest
import io.xoboro.core.domain.HistoricalEventRepository
import io.xoboro.core.domain.HistoricalEventSortField
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.SortDirection
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeOpsTest {
  @Test
  fun `forbids non-administrators on every administrator route without writes`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      for (route in ADMINISTRATOR_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
            bearerAuth(fixture.readerToken)
          }

        assertEquals(HttpStatusCode.Forbidden, response.status, route.path)
        assertEquals(route.forbiddenCode, response.body<XoboroApiError>().code, route.path)
      }
      fixture.assertNoWrites()
      assertEquals(0, fixture.settings.findCalls)
      assertEquals(0, fixture.clientSettings.totalReads)
      assertEquals(0, fixture.authenticationActivities.totalReads)
      assertEquals(0, fixture.history.findAllCalls)
    }

  @Test
  fun `requires authentication on every operations route`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      for (route in ALL_ROUTES) {
        val response =
          client.request(route.path) {
            method = route.method
          }

        assertEquals(HttpStatusCode.Unauthorized, response.status, route.path)
      }
      fixture.assertNoWrites()
    }

  @Test
  fun `rejects cross-site cookie mutation without writes`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      val response =
        client.put(CLIENT_SETTINGS_PATH) {
          cookie(XOBORO_SESSION_COOKIE, fixture.adminToken)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          contentType(ContentType.Application.Json)
          setBody(mapOf("reader.theme" to XoboroClientSettingWriteRequest("dark")))
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(
        CrossSiteRequestRejectedException.CODE,
        response.body<XoboroApiError>().code,
      )
      fixture.assertNoWrites()
    }

  @Test
  fun `accepts bearer mutation with a cross-site origin`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      val response =
        client.put(CLIENT_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
          header(HttpHeaders.Origin, "https://cross-site.example.invalid")
          header("Sec-Fetch-Site", "cross-site")
          contentType(ContentType.Application.Json)
          setBody(mapOf("reader.theme" to XoboroClientSettingWriteRequest("dark")))
        }

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(
        ClientSetting("dark"),
        fixture.clientSettings.forUser(ADMIN_USER_ID)["reader.theme"],
      )
    }

  @Test
  fun `server settings response never contains the remember-me signing key`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      val response =
        client.get(SERVER_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val rawBody = response.bodyAsText()
      assertTrue(!rawBody.contains(SYNTHETIC_REMEMBER_ME_SECRET), rawBody)
      assertEquals(7, Json.decodeFromString<XoboroServerSettingsResponse>(rawBody).taskPoolSize)
    }

  @Test
  fun `partial server settings update leaves absent fields unchanged`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)
      val before =
        client.get(SERVER_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
        }.body<XoboroServerSettingsResponse>()

      val update =
        client.put(SERVER_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody("""{"taskPoolSize":12}""")
        }
      val after =
        client.get(SERVER_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
        }.body<XoboroServerSettingsResponse>()

      assertEquals(HttpStatusCode.NoContent, update.status)
      assertEquals(12, after.taskPoolSize)
      assertEquals(before.copy(taskPoolSize = 12), after)
      assertEquals(listOf("TASK_POOL_SIZE" to "12"), fixture.settings.puts)
      assertEquals(emptyList(), fixture.settings.deletes)
    }

  @Test
  fun `effective client settings merge per-user values over globals`() =
    testApplication {
      val fixture = Fixture()
      fixture.clientSettings.global["reader.theme"] =
        ClientSetting("dark", allowUnauthorized = true)
      fixture.clientSettings.global["reader.locale"] =
        ClientSetting("en", allowUnauthorized = false)
      fixture.clientSettings.forUser(ADMIN_USER_ID)["reader.theme"] = ClientSetting("light")
      installOperations(fixture)

      val response =
        client.get(CLIENT_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val settings = response.body<Map<String, XoboroClientSettingResponse>>()
      assertEquals("light", settings["reader.theme"]?.value)
      assertEquals(null, settings["reader.theme"]?.allowUnauthorized)
      assertEquals("en", settings["reader.locale"]?.value)
      assertEquals(false, settings["reader.locale"]?.allowUnauthorized)
    }

  @Test
  fun `per-user client settings update writes only the caller scope`() =
    testApplication {
      val fixture = Fixture()
      fixture.clientSettings.global["reader.theme"] =
        ClientSetting("dark", allowUnauthorized = true)
      installOperations(fixture)

      val response =
        client.put(CLIENT_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(
            mapOf(
              "reader.theme" to XoboroClientSettingWriteRequest("light"),
              "reader.locale" to XoboroClientSettingWriteRequest("en"),
            ),
          )
        }

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(
        mapOf(
          "reader.theme" to ClientSetting("light"),
          "reader.locale" to ClientSetting("en"),
        ),
        fixture.clientSettings.forUser(ADMIN_USER_ID),
      )
      assertEquals(
        mapOf("reader.theme" to ClientSetting("dark", allowUnauthorized = true)),
        fixture.clientSettings.global,
      )
      assertEquals(1, fixture.clientSettings.saveForUserCalls)
      assertEquals(0, fixture.clientSettings.saveGlobalCalls)
    }

  @Test
  fun `non-administrator cannot write global client settings`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      val response =
        client.put(GLOBAL_CLIENT_SETTINGS_PATH) {
          bearerAuth(fixture.readerToken)
          contentType(ContentType.Application.Json)
          setBody(
            mapOf(
              "reader.theme" to
                XoboroClientSettingGlobalWriteRequest("dark", allowUnauthorized = true),
            ),
          )
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("client_settings_forbidden", response.body<XoboroApiError>().code)
      assertEquals(0, fixture.clientSettings.saveGlobalCalls)
      assertEquals(emptyMap(), fixture.clientSettings.global)
    }

  @Test
  fun `personal authentication activity contains only the caller rows`() =
    testApplication {
      val fixture = Fixture()
      fixture.authenticationActivities.seed(
        authenticationActivity(ADMIN_USER_ID, 1_000, "admin-first"),
        authenticationActivity(READER_USER_ID, 1_500, "reader-only"),
        authenticationActivity(ADMIN_USER_ID, 2_000, "admin-second"),
      )
      installOperations(fixture)

      val response =
        client.get(ME_AUTHENTICATION_ACTIVITY_PATH) {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val page = response.body<XoboroPageResponse<XoboroAuthenticationActivityResponse>>()
      assertEquals(2L, page.totalItems)
      assertEquals(setOf("admin-first", "admin-second"), page.items.mapNotNull { it.source }.toSet())
      assertTrue(page.items.none { it.userId == READER_USER_ID.value })
      assertEquals(ADMIN_USER_ID, fixture.authenticationActivities.lastRequestedUser?.id)
    }

  @Test
  fun `administrator authentication activity is paged and enforces size ceiling`() =
    testApplication {
      val fixture = Fixture()
      fixture.authenticationActivities.seed(
        *(0 until 25)
          .map { index ->
            authenticationActivity(ADMIN_USER_ID, index.toLong(), "activity-$index")
          }.toTypedArray(),
      )
      installOperations(fixture)

      val response =
        client.get(AUTHENTICATION_ACTIVITY_PATH) {
          bearerAuth(fixture.adminToken)
        }
      val oversized =
        client.get("$AUTHENTICATION_ACTIVITY_PATH?size=201") {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val page = response.body<XoboroPageResponse<XoboroAuthenticationActivityResponse>>()
      assertEquals(0, page.page)
      assertEquals(20, page.size)
      assertEquals(25L, page.totalItems)
      assertEquals(2, page.totalPages)
      assertEquals(20, page.items.size)
      assertEquals(HttpStatusCode.BadRequest, oversized.status)
      assertEquals("invalid_query", oversized.body<XoboroApiError>().code)
      assertEquals(1, fixture.authenticationActivities.findAllCalls)
    }

  @Test
  fun `history is administrator-only and paged`() =
    testApplication {
      val fixture = Fixture()
      fixture.history.seed(
        *(0 until 25)
          .map { index ->
            HistoricalEvent(
              id = "history-$index",
              type = "SYNTHETIC_EVENT",
              timestampMillis = index.toLong(),
              bookId = BookId("book-$index"),
              seriesId = SeriesId("series-$index"),
              properties = mapOf("index" to index.toString()),
            )
          }.toTypedArray(),
      )
      installOperations(fixture)

      val forbidden =
        client.get(HISTORY_PATH) {
          bearerAuth(fixture.readerToken)
        }
      val response =
        client.get(HISTORY_PATH) {
          bearerAuth(fixture.adminToken)
        }

      assertEquals(HttpStatusCode.Forbidden, forbidden.status)
      assertEquals("history_forbidden", forbidden.body<XoboroApiError>().code)
      assertEquals(HttpStatusCode.OK, response.status)
      val page = response.body<XoboroPageResponse<XoboroHistoricalEventResponse>>()
      assertEquals(0, page.page)
      assertEquals(20, page.size)
      assertEquals(25L, page.totalItems)
      assertEquals(20, page.items.size)
      assertEquals(1, fixture.history.findAllCalls)
    }

  @Test
  fun `malformed settings JSON returns invalid request without writes`() =
    testApplication {
      val fixture = Fixture()
      installOperations(fixture)

      val paths =
        listOf(
          SERVER_SETTINGS_PATH,
          CLIENT_SETTINGS_PATH,
          GLOBAL_CLIENT_SETTINGS_PATH,
        )
      for (path in paths) {
        val response =
          client.put(path) {
            bearerAuth(fixture.adminToken)
            contentType(ContentType.Application.Json)
            setBody("{")
          }
        assertEquals(HttpStatusCode.BadRequest, response.status, path)
        assertEquals("invalid_request", response.body<XoboroApiError>().code, path)
      }
      fixture.assertNoWrites()
    }

  @Test
  fun `global client settings deletion removes exactly the requested keys`() =
    testApplication {
      val fixture = Fixture()
      fixture.clientSettings.global["reader.theme"] =
        ClientSetting("dark", allowUnauthorized = true)
      fixture.clientSettings.global["reader.locale"] =
        ClientSetting("en", allowUnauthorized = false)
      installOperations(fixture)

      val response =
        client.delete(GLOBAL_CLIENT_SETTINGS_PATH) {
          bearerAuth(fixture.adminToken)
          contentType(ContentType.Application.Json)
          setBody(setOf("reader.theme"))
        }

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(
        mapOf("reader.locale" to ClientSetting("en", allowUnauthorized = false)),
        fixture.clientSettings.global,
      )
      assertEquals(setOf("reader.theme"), fixture.clientSettings.lastDeletedGlobalKeys)
      assertEquals(1, fixture.clientSettings.deleteGlobalCalls)
    }

  private fun ApplicationTestBuilder.installOperations(fixture: Fixture) {
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        exception<CrossSiteRequestRejectedException> { call, cause ->
          call.respond(
            HttpStatusCode.Forbidden,
            XoboroApiError(
              CrossSiteRequestRejectedException.CODE,
              requireNotNull(cause.message),
            ),
          )
        }
        exception<XoboroInvalidQueryException> { call, cause ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_query", requireNotNull(cause.message)),
          )
        }
        exception<BadRequestException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed request"),
          )
        }
        exception<ContentTransformationException> { call, _ ->
          call.respond(
            HttpStatusCode.BadRequest,
            XoboroApiError("invalid_request", "Malformed JSON request"),
          )
        }
      }
      routing {
        xoboroNativeOpsRoutes(
          serverSettings = fixture.serverSettings,
          clientSettings = fixture.clientSettingsLifecycle,
          authenticationActivities = fixture.authenticationActivityLifecycle,
          history = fixture.history,
        )
      }
    }
    client =
      createClient {
        install(ClientContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }
  }

  private class Fixture {
    private val users =
      InMemoryUserRepository(
        listOf(
          syntheticUser(
            id = ADMIN_USER_ID,
            email = "admin@example.invalid",
            roles = setOf(UserRole.ADMIN),
          ),
          syntheticUser(
            id = READER_USER_ID,
            email = "reader@example.invalid",
            roles = setOf(UserRole.PAGE_STREAMING),
          ),
        ),
      )
    val settings =
      RecordingServerSettingStore(
        mapOf(
          "REMEMBER_ME_KEY" to SYNTHETIC_REMEMBER_ME_SECRET,
          "TASK_POOL_SIZE" to "7",
          "DELETE_EMPTY_COLLECTIONS" to "true",
          "SERVER_CONTEXT_PATH" to "/database",
        ),
      )
    val serverSettings =
      ServerSettingsLifecycle(
        store = settings,
        configuredServerPort = 8_080,
        effectiveServerPort = { 9_090 },
        configuredServerContextPath = "/configured",
        effectiveServerContextPath = { "/effective" },
        configuredKepubifyPath = "/synthetic/configured/kepubify",
        effectiveKepubifyPath = { "/synthetic/effective/kepubify" },
        defaultTaskPoolSize = 3,
        rememberMeKeyFactory = { SYNTHETIC_REMEMBER_ME_SECRET },
      )
    val clientSettings = RecordingClientSettingsRepository()
    val clientSettingsLifecycle = ClientSettingsLifecycle(clientSettings)
    val authenticationActivities = RecordingAuthenticationActivityRepository()
    val authenticationActivityLifecycle =
      AuthenticationActivityLifecycle(
        activities = authenticationActivities,
        currentTimeMillis = { 3_000 },
      )
    val history = RecordingHistoricalEventRepository()
    val sessions =
      UserSessionLifecycle(
        users = users,
        sessions = InMemoryUserSessionRepository(),
        tokenEncoder = TokenEncoder { it },
        plainTokenFactory =
          listOf("admin-token", "reader-token")
            .iterator()
            .let { tokens -> tokens::next },
        currentTimeMillis = { 1_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val adminToken = sessions.create(assertNotNull(users.findByIdOrNull(ADMIN_USER_ID))).plainToken
    val readerToken = sessions.create(assertNotNull(users.findByIdOrNull(READER_USER_ID))).plainToken

    fun assertNoWrites() {
      assertEquals(0, settings.putCalls)
      assertEquals(0, settings.deleteCalls)
      assertEquals(0, clientSettings.totalWrites)
      assertEquals(0, authenticationActivities.totalWrites)
      assertEquals(0, history.insertCalls)
    }
  }

  private class RecordingServerSettingStore(
    initialValues: Map<String, String>,
  ) : ServerSettingStore {
    private val values = initialValues.toMutableMap()
    val puts = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()
    var findCalls = 0
      private set
    val putCalls: Int
      get() = puts.size
    val deleteCalls: Int
      get() = deletes.size

    override fun find(key: String): String? {
      findCalls += 1
      return values[key]
    }

    override fun findOrCreate(
      key: String,
      valueFactory: () -> String,
    ): String = values.getOrPut(key, valueFactory)

    override fun put(
      key: String,
      value: String,
    ) {
      puts += key to value
      values[key] = value
    }

    override fun delete(key: String) {
      deletes += key
      values.remove(key)
    }
  }

  private class RecordingClientSettingsRepository : ClientSettingsRepository {
    val global = linkedMapOf<String, ClientSetting>()
    private val perUser = linkedMapOf<UserId, MutableMap<String, ClientSetting>>()
    var findGlobalCalls = 0
      private set
    var findForUserCalls = 0
      private set
    var saveGlobalCalls = 0
      private set
    var saveForUserCalls = 0
      private set
    var deleteGlobalCalls = 0
      private set
    var deleteForUserCalls = 0
      private set
    var lastDeletedGlobalKeys: Set<String>? = null
      private set
    val totalReads: Int
      get() = findGlobalCalls + findForUserCalls
    val totalWrites: Int
      get() = saveGlobalCalls + saveForUserCalls + deleteGlobalCalls + deleteForUserCalls

    fun forUser(userId: UserId): MutableMap<String, ClientSetting> =
      perUser.getOrPut(userId, ::linkedMapOf)

    override fun findGlobal(onlyUnauthorized: Boolean): Map<String, ClientSetting> {
      findGlobalCalls += 1
      return if (onlyUnauthorized) {
        global.filterValues { it.allowUnauthorized == true }
      } else {
        global.toMap()
      }
    }

    override fun findForUser(userId: UserId): Map<String, ClientSetting> {
      findForUserCalls += 1
      return perUser[userId]?.toMap().orEmpty()
    }

    override fun saveGlobal(settings: Map<String, ClientSetting>) {
      saveGlobalCalls += 1
      global.putAll(settings)
    }

    override fun saveForUser(
      userId: UserId,
      settings: Map<String, ClientSetting>,
    ) {
      saveForUserCalls += 1
      forUser(userId).putAll(settings)
    }

    override fun deleteGlobal(keys: Set<String>) {
      deleteGlobalCalls += 1
      lastDeletedGlobalKeys = keys.toSet()
      keys.forEach(global::remove)
    }

    override fun deleteForUser(
      userId: UserId,
      keys: Set<String>,
    ) {
      deleteForUserCalls += 1
      keys.forEach(forUser(userId)::remove)
    }
  }

  private class RecordingAuthenticationActivityRepository : AuthenticationActivityRepository {
    private val values = mutableListOf<AuthenticationActivity>()
    var findAllCalls = 0
      private set
    var findAllByUserCalls = 0
      private set
    var insertCalls = 0
      private set
    var deleteOlderThanCalls = 0
      private set
    var lastRequestedUser: User? = null
      private set
    val totalReads: Int
      get() = findAllCalls + findAllByUserCalls
    val totalWrites: Int
      get() = insertCalls + deleteOlderThanCalls

    fun seed(vararg activities: AuthenticationActivity) {
      activities.forEach(values::add)
    }

    override fun findAll(request: AuthenticationActivityPageRequest): AuthenticationActivityPage {
      findAllCalls += 1
      return values.toPage(request)
    }

    override fun findAllByUser(
      user: User,
      request: AuthenticationActivityPageRequest,
    ): AuthenticationActivityPage {
      findAllByUserCalls += 1
      lastRequestedUser = user
      return values.filter { it.userId == user.id }.toPage(request)
    }

    override fun findMostRecentByUser(
      user: User,
      apiKeyId: ApiKeyId?,
    ): AuthenticationActivity? =
      values
        .asSequence()
        .filter { it.userId == user.id && (apiKeyId == null || it.apiKeyId == apiKeyId) }
        .maxByOrNull(AuthenticationActivity::dateTimeMillis)

    override fun insert(activity: AuthenticationActivity) {
      insertCalls += 1
      values += activity
    }

    override fun deleteOlderThan(dateTimeMillis: Long): Int {
      deleteOlderThanCalls += 1
      val before = values.size
      values.removeAll { it.dateTimeMillis < dateTimeMillis }
      return before - values.size
    }

    private fun List<AuthenticationActivity>.toPage(
      request: AuthenticationActivityPageRequest,
    ): AuthenticationActivityPage {
      val comparator: Comparator<AuthenticationActivity> =
        when (request.sortField) {
          AuthenticationActivitySortField.DATE_TIME ->
            compareBy(AuthenticationActivity::dateTimeMillis)
          AuthenticationActivitySortField.EMAIL ->
            compareBy { it.email.orEmpty() }
          AuthenticationActivitySortField.SUCCESS ->
            compareBy(AuthenticationActivity::success)
          AuthenticationActivitySortField.IP ->
            compareBy { it.ip.orEmpty() }
          AuthenticationActivitySortField.ERROR ->
            compareBy { it.error.orEmpty() }
          AuthenticationActivitySortField.USER_ID ->
            compareBy { it.userId?.value.orEmpty() }
          AuthenticationActivitySortField.USER_AGENT ->
            compareBy { it.userAgent.orEmpty() }
        }
      val ordered =
        sortedWith(
          if (request.sortDirection == SortDirection.DESCENDING) {
            comparator.reversed()
          } else {
            comparator
          },
        )
      val content =
        if (request.unpaged) {
          ordered
        } else {
          ordered.pageSlice(request.pageNumber, request.pageSize)
        }
      return AuthenticationActivityPage(
        content = content,
        totalElements = size.toLong(),
        request = request,
      )
    }
  }

  private class RecordingHistoricalEventRepository : HistoricalEventRepository {
    private val values = mutableListOf<HistoricalEvent>()
    var findAllCalls = 0
      private set
    var insertCalls = 0
      private set

    fun seed(vararg events: HistoricalEvent) {
      events.forEach(values::add)
    }

    override fun findAll(request: HistoricalEventPageRequest): HistoricalEventPage {
      findAllCalls += 1
      val comparator: Comparator<HistoricalEvent> =
        when (request.sortField) {
          HistoricalEventSortField.TYPE -> compareBy(HistoricalEvent::type)
          HistoricalEventSortField.BOOK_ID -> compareBy { it.bookId?.value.orEmpty() }
          HistoricalEventSortField.SERIES_ID -> compareBy { it.seriesId?.value.orEmpty() }
          HistoricalEventSortField.TIMESTAMP -> compareBy(HistoricalEvent::timestampMillis)
        }
      val ordered =
        values.sortedWith(
          if (request.direction == SortDirection.DESCENDING) {
            comparator.reversed()
          } else {
            comparator
          },
        )
      val content =
        if (request.unpaged) {
          ordered
        } else {
          ordered.pageSlice(request.page, request.size)
        }
      return HistoricalEventPage(
        content = content,
        totalElements = values.size.toLong(),
        request = request,
      )
    }

    override fun insert(event: HistoricalEvent) {
      insertCalls += 1
      values += event
    }
  }

  private class InMemoryUserRepository(
    users: Collection<User>,
  ) : UserRepository {
    private val values = users.associateByTo(linkedMapOf(), User::id)

    override fun count(): Long = values.size.toLong()

    override fun findByIdOrNull(id: UserId): User? = values[id]

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      values.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = values.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      values[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (values.isNotEmpty()) return false
      values[user.id] = user
      return true
    }

    override fun update(user: User) {
      values[user.id] = user
    }

    override fun delete(id: UserId) {
      values.remove(id)
    }
  }

  private data class OperationsRoute(
    val method: HttpMethod,
    val path: String,
    val forbiddenCode: String? = null,
  )

  companion object {
    private const val SERVER_SETTINGS_PATH = "$XOBORO_API_PREFIX/server-settings"
    private const val CLIENT_SETTINGS_PATH = "$XOBORO_API_PREFIX/client-settings"
    private const val GLOBAL_CLIENT_SETTINGS_PATH = "$CLIENT_SETTINGS_PATH/global"
    private const val AUTHENTICATION_ACTIVITY_PATH =
      "$XOBORO_API_PREFIX/authentication-activity"
    private const val ME_AUTHENTICATION_ACTIVITY_PATH =
      "$XOBORO_API_PREFIX/me/authentication-activity"
    private const val HISTORY_PATH = "$XOBORO_API_PREFIX/history"
    private const val SYNTHETIC_REMEMBER_ME_SECRET =
      "synthetic-remember-me-secret-should-never-leak"
    private val ADMIN_USER_ID = UserId("admin-user")
    private val READER_USER_ID = UserId("reader-user")
    private val ADMINISTRATOR_ROUTES =
      listOf(
        OperationsRoute(
          HttpMethod.Get,
          SERVER_SETTINGS_PATH,
          "server_settings_forbidden",
        ),
        OperationsRoute(
          HttpMethod.Put,
          SERVER_SETTINGS_PATH,
          "server_settings_forbidden",
        ),
        OperationsRoute(
          HttpMethod.Get,
          GLOBAL_CLIENT_SETTINGS_PATH,
          "client_settings_forbidden",
        ),
        OperationsRoute(
          HttpMethod.Put,
          GLOBAL_CLIENT_SETTINGS_PATH,
          "client_settings_forbidden",
        ),
        OperationsRoute(
          HttpMethod.Delete,
          GLOBAL_CLIENT_SETTINGS_PATH,
          "client_settings_forbidden",
        ),
        OperationsRoute(
          HttpMethod.Get,
          AUTHENTICATION_ACTIVITY_PATH,
          "authentication_activity_forbidden",
        ),
        OperationsRoute(HttpMethod.Get, HISTORY_PATH, "history_forbidden"),
      )
    private val ALL_ROUTES =
      ADMINISTRATOR_ROUTES +
        listOf(
          OperationsRoute(HttpMethod.Get, CLIENT_SETTINGS_PATH),
          OperationsRoute(HttpMethod.Put, CLIENT_SETTINGS_PATH),
          OperationsRoute(HttpMethod.Get, ME_AUTHENTICATION_ACTIVITY_PATH),
        )

    private fun authenticationActivity(
      userId: UserId,
      dateTimeMillis: Long,
      source: String,
    ): AuthenticationActivity =
      AuthenticationActivity(
        userId = userId,
        email = "${userId.value}@example.invalid",
        ip = "192.0.2.1",
        userAgent = "Synthetic client",
        success = true,
        dateTimeMillis = dateTimeMillis,
        source = source,
      )

    private fun syntheticUser(
      id: UserId,
      email: String,
      roles: Set<UserRole>,
    ): User =
      User(
        id = id,
        email = email,
        passwordHash = "synthetic-hash",
        roles = roles,
        sharesAllLibraries = true,
        createdAtMillis = 1,
      )

    private fun <T> List<T>.pageSlice(
      page: Int,
      size: Int,
    ): List<T> {
      val start = page.toLong() * size
      if (start >= this.size) return emptyList()
      val fromIndex = start.toInt()
      return subList(fromIndex, minOf(fromIndex + size, this.size))
    }
  }
}
