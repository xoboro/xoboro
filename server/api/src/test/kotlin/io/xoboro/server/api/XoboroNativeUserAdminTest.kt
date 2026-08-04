package io.xoboro.server.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.xoboro.core.application.ApiKeyLifecycle
import io.xoboro.core.application.PasswordHasher
import io.xoboro.core.application.TokenEncoder
import io.xoboro.core.application.UserLifecycle
import io.xoboro.core.application.UserSessionLifecycle
import io.xoboro.core.domain.AgeRestriction
import io.xoboro.core.domain.ApiKey
import io.xoboro.core.domain.ApiKeyHashAlreadyExistsException
import io.xoboro.core.domain.ApiKeyId
import io.xoboro.core.domain.ApiKeyRepository
import io.xoboro.core.domain.ContentRestrictions
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.RestrictionMode
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserEmailAlreadyExistsException
import io.xoboro.core.domain.UserId
import io.xoboro.core.domain.UserRepository
import io.xoboro.core.domain.UserRole
import io.xoboro.server.security.InMemoryUserSessionRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XoboroNativeUserAdminTest {
  @Test
  fun `non-administrator is forbidden from every administration route without writes`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)
      fixture.resetObservations()

      val responses =
        listOf(
          client.get("$XOBORO_API_PREFIX/users") { bearerAuth(token) },
          client.post("$XOBORO_API_PREFIX/users") {
            bearerAuth(token)
            jsonBody(validCreationRequest())
          },
          client.put("$XOBORO_API_PREFIX/users/${fixture.admin.id.value}") {
            bearerAuth(token)
            jsonBody(validUpdateRequest())
          },
          client.put("$XOBORO_API_PREFIX/users/${fixture.admin.id.value}/password") {
            bearerAuth(token)
            jsonBody(XoboroAdministratorPasswordUpdateRequest("replacement-password"))
          },
          client.delete("$XOBORO_API_PREFIX/users/${fixture.admin.id.value}") {
            bearerAuth(token)
          },
        )

      responses.forEach {
        assertEquals(HttpStatusCode.Forbidden, it.status)
        assertEquals("user_administration_forbidden", it.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.userRepository.writeCount)
      assertEquals(0, fixture.apiKeyRepository.writeCount)
    }

  @Test
  fun `non-administrator receives forbidden before a nonexistent target is resolved`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)
      fixture.resetObservations()

      val response =
        client.put("$XOBORO_API_PREFIX/users/does-not-exist") {
          bearerAuth(token)
          jsonBody(validUpdateRequest())
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("user_administration_forbidden", response.body<XoboroApiError>().code)
      assertFalse(UserId("does-not-exist") in fixture.userRepository.findByIdCalls)
    }

  @Test
  fun `every self-service and administration route requires authentication`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val userId = fixture.reader.id.value
      val apiKeyId = "missing-key"
      val requests =
        listOf<suspend () -> HttpResponse>(
          { client.get("$XOBORO_API_PREFIX/me") },
          {
            client.put("$XOBORO_API_PREFIX/me/password") {
              jsonBody(XoboroOwnPasswordUpdateRequest("reader-password", "new-password"))
            }
          },
          { client.get("$XOBORO_API_PREFIX/me/api-keys") },
          {
            client.post("$XOBORO_API_PREFIX/me/api-keys") {
              jsonBody(XoboroApiKeyCreationRequest("Synthetic key"))
            }
          },
          { client.delete("$XOBORO_API_PREFIX/me/api-keys/$apiKeyId") },
          { client.get("$XOBORO_API_PREFIX/users") },
          {
            client.post("$XOBORO_API_PREFIX/users") {
              jsonBody(validCreationRequest())
            }
          },
          {
            client.put("$XOBORO_API_PREFIX/users/$userId") {
              jsonBody(validUpdateRequest())
            }
          },
          {
            client.put("$XOBORO_API_PREFIX/users/$userId/password") {
              jsonBody(XoboroAdministratorPasswordUpdateRequest("replacement-password"))
            }
          },
          { client.delete("$XOBORO_API_PREFIX/users/$userId") },
        )

      requests.forEach { request ->
        val response = request()
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("authentication_required", response.body<XoboroApiError>().code)
      }
    }

  @Test
  fun `cross-site cookie mutations are rejected before any write`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val cookieToken = fixture.bearerToken(fixture.admin)
      fixture.resetObservations()

      val responses =
        listOf(
          client.put("$XOBORO_API_PREFIX/me/password") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
            jsonBody(XoboroOwnPasswordUpdateRequest("admin-password", "new-password"))
          },
          client.post("$XOBORO_API_PREFIX/me/api-keys") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
            jsonBody(XoboroApiKeyCreationRequest("Synthetic key"))
          },
          client.delete("$XOBORO_API_PREFIX/me/api-keys/missing-key") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
          },
          client.post("$XOBORO_API_PREFIX/users") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
            jsonBody(validCreationRequest())
          },
          client.put("$XOBORO_API_PREFIX/users/${fixture.reader.id.value}") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
            jsonBody(validUpdateRequest())
          },
          client.put("$XOBORO_API_PREFIX/users/${fixture.reader.id.value}/password") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
            jsonBody(XoboroAdministratorPasswordUpdateRequest("replacement-password"))
          },
          client.delete("$XOBORO_API_PREFIX/users/${fixture.reader.id.value}") {
            cookie(XOBORO_SESSION_COOKIE, cookieToken)
            crossSite()
          },
        )

      responses.forEach {
        assertEquals(HttpStatusCode.Forbidden, it.status)
        assertEquals("cross_site_request_rejected", it.body<XoboroApiError>().code)
      }
      assertEquals(0, fixture.userRepository.writeCount)
      assertEquals(0, fixture.apiKeyRepository.writeCount)
    }

  @Test
  fun `bearer mutation succeeds despite a cross-site origin header`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)

      val response =
        client.post("$XOBORO_API_PREFIX/users") {
          bearerAuth(token)
          crossSite()
          jsonBody(validCreationRequest())
        }

      assertEquals(HttpStatusCode.Created, response.status)
      assertEquals("created@example.invalid", response.body<XoboroUserAdministrationResponse>().email)
    }

  @Test
  fun `get me returns only safe caller data`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)

      val response =
        client.get("$XOBORO_API_PREFIX/me") {
          bearerAuth(token)
        }
      val rawBody = response.bodyAsText()

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(fixture.reader.id.value, Json.decodeFromString<XoboroSelfServiceUserResponse>(rawBody).id)
      assertFalse(rawBody.contains(fixture.reader.passwordHash))
      assertFalse(rawBody.contains(token))
      assertFalse(rawBody.contains("passwordHash"))
      assertFalse(rawBody.contains("accessToken"))
    }

  @Test
  fun `own password change verifies current password and expires sessions`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)
      fixture.invalidatedUserIds.clear()

      val response =
        client.put("$XOBORO_API_PREFIX/me/password") {
          bearerAuth(token)
          jsonBody(XoboroOwnPasswordUpdateRequest("reader-password", "new-reader-password"))
        }

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(listOf(fixture.reader.id), fixture.invalidatedUserIds)
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("$XOBORO_API_PREFIX/me") { bearerAuth(token) }.status,
      )
      assertNotNull(fixture.users.authenticate(fixture.reader.email, "new-reader-password"))
    }

  @Test
  fun `wrong current password leaves password and sessions unchanged`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)
      fixture.invalidatedUserIds.clear()

      val response =
        client.put("$XOBORO_API_PREFIX/me/password") {
          bearerAuth(token)
          jsonBody(XoboroOwnPasswordUpdateRequest("wrong-password", "new-reader-password"))
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("invalid_credentials", response.body<XoboroApiError>().code)
      assertTrue(fixture.invalidatedUserIds.isEmpty())
      assertNotNull(fixture.users.authenticate(fixture.reader.email, "reader-password"))
      assertNull(fixture.users.authenticate(fixture.reader.email, "new-reader-password"))
      assertEquals(
        HttpStatusCode.OK,
        client.get("$XOBORO_API_PREFIX/me") { bearerAuth(token) }.status,
      )
    }

  @Test
  fun `api key creation returns token once and listing exposes metadata only`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)

      val creation =
        client.post("$XOBORO_API_PREFIX/me/api-keys") {
          bearerAuth(token)
          jsonBody(XoboroApiKeyCreationRequest("Synthetic reader key"))
        }
      val created = creation.body<XoboroCreatedApiKeyResponse>()
      val stored = assertNotNull(fixture.apiKeyRepository.findByIdOrNull(ApiKeyId(created.id)))

      assertEquals(HttpStatusCode.Created, creation.status)
      assertTrue(created.token.isNotBlank())
      val listing =
        client.get("$XOBORO_API_PREFIX/me/api-keys") {
          bearerAuth(token)
        }
      val rawBody = listing.bodyAsText()
      assertEquals(HttpStatusCode.OK, listing.status)
      assertEquals(created.id, Json.decodeFromString<List<XoboroApiKeyMetadataResponse>>(rawBody).single().id)
      assertFalse(rawBody.contains("\"token\""))
      assertFalse(rawBody.contains(stored.keyHash))
      assertFalse(rawBody.contains("keyHash"))
    }

  @Test
  fun `deleting another users api key returns not found and preserves it`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val otherKey = assertNotNull(fixture.apiKeys.create(fixture.admin.id, "Administrator key"))
      val token = fixture.bearerToken(fixture.reader)

      val response =
        client.delete("$XOBORO_API_PREFIX/me/api-keys/${otherKey.apiKey.id.value}") {
          bearerAuth(token)
        }

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("api_key_not_found", response.body<XoboroApiError>().code)
      assertNotNull(fixture.apiKeyRepository.findByIdOrNull(otherKey.apiKey.id))
    }

  @Test
  fun `all me operations target only the authenticated user`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.reader)
      fixture.resetObservations()

      assertEquals(HttpStatusCode.OK, client.get("$XOBORO_API_PREFIX/me") { bearerAuth(token) }.status)
      assertEquals(
        HttpStatusCode.NoContent,
        client.put("$XOBORO_API_PREFIX/me/password") {
          bearerAuth(token)
          jsonBody(XoboroOwnPasswordUpdateRequest("reader-password", "updated-password"))
        }.status,
      )
      val refreshedToken = fixture.bearerToken(fixture.reader)
      assertEquals(
        HttpStatusCode.OK,
        client.get("$XOBORO_API_PREFIX/me/api-keys") { bearerAuth(refreshedToken) }.status,
      )
      val creation =
        client.post("$XOBORO_API_PREFIX/me/api-keys") {
          bearerAuth(refreshedToken)
          jsonBody(XoboroApiKeyCreationRequest("Reader-owned key"))
        }
      val keyId = creation.body<XoboroCreatedApiKeyResponse>().id
      assertEquals(
        HttpStatusCode.NoContent,
        client.delete("$XOBORO_API_PREFIX/me/api-keys/$keyId") {
          bearerAuth(refreshedToken)
        }.status,
      )

      assertTrue(fixture.userRepository.findByIdCalls.isNotEmpty())
      assertTrue(fixture.userRepository.findByIdCalls.all { it == fixture.reader.id })
      assertTrue(fixture.userRepository.updatedIds.all { it == fixture.reader.id })
      assertTrue(fixture.apiKeyRepository.userIdCalls.all { it == fixture.reader.id })
      assertFalse(fixture.userRepository.findByIdCalls.contains(fixture.admin.id))
    }

  @Test
  fun `create user handles success duplicate email and invalid payloads`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)
      val request = validCreationRequest()

      val created =
        client.post("$XOBORO_API_PREFIX/users") {
          bearerAuth(token)
          jsonBody(request)
        }
      assertEquals(HttpStatusCode.Created, created.status)
      assertEquals(request.email, created.body<XoboroUserAdministrationResponse>().email)

      val duplicate =
        client.post("$XOBORO_API_PREFIX/users") {
          bearerAuth(token)
          jsonBody(request)
        }
      assertEquals(HttpStatusCode.Conflict, duplicate.status)
      assertEquals("user_email_already_exists", duplicate.body<XoboroApiError>().code)

      listOf(
        request.copy(email = ""),
        request.copy(email = "invalid-email"),
        request.copy(password = ""),
      ).forEach { invalidRequest ->
        val invalid =
          client.post("$XOBORO_API_PREFIX/users") {
            bearerAuth(token)
            jsonBody(invalidRequest)
          }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("invalid_request", invalid.body<XoboroApiError>().code)
      }
    }

  @Test
  fun `unknown roles and library identifiers are rejected instead of dropped`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)
      val creation = validCreationRequest()

      // A mistyped role must not silently create a user with fewer rights than requested.
      listOf(
        creation.copy(roles = listOf("ADMIM")),
        creation.copy(roles = listOf("PAGE_STREAMING", "NOT_A_ROLE")),
        creation.copy(sharedLibraryIds = listOf("")),
      ).forEach { invalidRequest ->
        val invalid =
          client.post("$XOBORO_API_PREFIX/users") {
            bearerAuth(token)
            jsonBody(invalidRequest)
          }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("invalid_request", invalid.body<XoboroApiError>().code)
      }
      assertNull(fixture.users.findByEmailIgnoreCaseOrNull(creation.email))

      // The same rule applies to updates, and an unknown role must not surface as 500.
      val update =
        client.put("$XOBORO_API_PREFIX/users/${fixture.reader.id.value}") {
          bearerAuth(token)
          jsonBody(validUpdateRequest().copy(roles = listOf("NOT_A_ROLE")))
        }
      assertEquals(HttpStatusCode.BadRequest, update.status)
      assertEquals("invalid_request", update.body<XoboroApiError>().code)
      assertEquals(
        fixture.reader.roles,
        fixture.users.findByIdOrNull(fixture.reader.id)?.roles,
      )
    }

  @Test
  fun `update user applies the complete submitted grants and restrictions`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)
      val request =
        XoboroUserUpdateRequest(
          roles = listOf("FILE_DOWNLOAD", "KOREADER_SYNC"),
          sharedLibraryIds = listOf("library-two", "library-one"),
          sharesAllLibraries = false,
          restrictions =
            XoboroContentRestrictionsRequest(
              ageRestriction = XoboroAgeRestrictionRequest(16, "EXCLUDE"),
              labelsAllow = listOf("Synthetic Allowed"),
              labelsExclude = listOf("Synthetic Excluded"),
            ),
        )

      val response =
        client.put("$XOBORO_API_PREFIX/users/${fixture.reader.id.value}") {
          bearerAuth(token)
          jsonBody(request)
        }
      val updated = response.body<XoboroUserAdministrationResponse>()

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(request.roles.sorted(), updated.roles)
      assertEquals(request.sharedLibraryIds.sorted(), updated.sharedLibraryIds)
      assertEquals(request.sharesAllLibraries, updated.sharesAllLibraries)
      assertEquals(16, updated.restrictions.ageRestriction?.age)
      assertEquals("EXCLUDE", updated.restrictions.ageRestriction?.mode)
      assertEquals(listOf("synthetic allowed"), updated.restrictions.labelsAllow)
      assertEquals(listOf("synthetic excluded"), updated.restrictions.labelsExclude)
    }

  @Test
  fun `administrator resets password without the old password and expires sessions`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val adminToken = fixture.bearerToken(fixture.admin)
      fixture.bearerToken(fixture.reader)
      fixture.invalidatedUserIds.clear()

      val response =
        client.put("$XOBORO_API_PREFIX/users/${fixture.reader.id.value}/password") {
          bearerAuth(adminToken)
          jsonBody(XoboroAdministratorPasswordUpdateRequest("administrator-reset-password"))
        }

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(listOf(fixture.reader.id), fixture.invalidatedUserIds)
      assertNotNull(
        fixture.users.authenticate(fixture.reader.email, "administrator-reset-password"),
      )
    }

  @Test
  fun `delete user succeeds once and then returns user not found`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)
      val path = "$XOBORO_API_PREFIX/users/${fixture.reader.id.value}"

      assertEquals(
        HttpStatusCode.NoContent,
        client.delete(path) { bearerAuth(token) }.status,
      )
      val repeated = client.delete(path) { bearerAuth(token) }
      assertEquals(HttpStatusCode.NotFound, repeated.status)
      assertEquals("user_not_found", repeated.body<XoboroApiError>().code)
    }

  @Test
  fun `administrator cannot delete their own account`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      fixture.addSecondAdministrator()
      val token = fixture.bearerToken(fixture.admin)

      val response =
        client.delete("$XOBORO_API_PREFIX/users/${fixture.admin.id.value}") {
          bearerAuth(token)
        }

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("cannot_delete_own_account", response.body<XoboroApiError>().code)
      assertNotNull(fixture.userRepository.userOrNull(fixture.admin.id))
    }

  @Test
  fun `last administrator is protected while a redundant administrator can be changed`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)
      val demotion =
        XoboroUserUpdateRequest(
          roles = listOf("PAGE_STREAMING"),
          sharedLibraryIds = emptyList(),
          sharesAllLibraries = true,
          restrictions = XoboroContentRestrictionsRequest(),
        )

      val protectedDemotion =
        client.put("$XOBORO_API_PREFIX/users/${fixture.admin.id.value}") {
          bearerAuth(token)
          jsonBody(demotion)
        }
      assertEquals(HttpStatusCode.Conflict, protectedDemotion.status)
      assertEquals("last_administrator_protected", protectedDemotion.body<XoboroApiError>().code)
      assertTrue(requireNotNull(fixture.userRepository.userOrNull(fixture.admin.id)).isAdmin)

      val secondAdmin = fixture.addSecondAdministrator()
      fixture.userRepository.beforeFindById = { id ->
        if (id == secondAdmin.id) {
          fixture.userRepository.beforeFindById = null
          fixture.userRepository.seed(
            fixture.admin.copy(roles = setOf(UserRole.PAGE_STREAMING)),
          )
        }
      }
      val protectedDelete =
        client.delete("$XOBORO_API_PREFIX/users/${secondAdmin.id.value}") {
          bearerAuth(token)
        }
      assertEquals(HttpStatusCode.Conflict, protectedDelete.status)
      assertEquals("last_administrator_protected", protectedDelete.body<XoboroApiError>().code)
      assertNotNull(fixture.userRepository.userOrNull(secondAdmin.id))

      fixture.userRepository.seed(fixture.admin)
      val successfulDemotion =
        client.put("$XOBORO_API_PREFIX/users/${secondAdmin.id.value}") {
          bearerAuth(token)
          jsonBody(demotion)
        }
      assertEquals(HttpStatusCode.OK, successfulDemotion.status)
      assertFalse(requireNotNull(fixture.userRepository.userOrNull(secondAdmin.id)).isAdmin)

      fixture.userRepository.seed(secondAdmin)
      val successfulDelete =
        client.delete("$XOBORO_API_PREFIX/users/${secondAdmin.id.value}") {
          bearerAuth(token)
        }
      assertEquals(HttpStatusCode.NoContent, successfulDelete.status)
      assertNull(fixture.userRepository.userOrNull(secondAdmin.id))
    }

  @Test
  fun `malformed mutation JSON returns invalid request`() =
    testApplication {
      val fixture = installNativeUserAdministration()
      val token = fixture.bearerToken(fixture.admin)

      val response =
        client.post("$XOBORO_API_PREFIX/users") {
          bearerAuth(token)
          contentType(ContentType.Application.Json)
          setBody("{")
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("invalid_request", response.body<XoboroApiError>().code)
    }

  private fun ApplicationTestBuilder.installNativeUserAdministration(): Fixture {
    val fixture = Fixture()
    application {
      install(ContentNegotiation) {
        json()
      }
      install(Authentication) {
        configureXoboroNativeAuthentication(fixture.sessions)
      }
      install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, status ->
          call.respond(
            status,
            XoboroApiError("authentication_required", status.description),
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
        exception<CrossSiteRequestRejectedException> { call, cause ->
          call.respond(
            HttpStatusCode.Forbidden,
            XoboroApiError(
              CrossSiteRequestRejectedException.CODE,
              requireNotNull(cause.message),
            ),
          )
        }
      }
      routing {
        xoboroNativeSelfServiceRoutes(fixture.users, fixture.apiKeys)
        xoboroNativeUserAdminRoutes(fixture.users)
      }
    }
    createClient {
      install(ClientContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
      }
    }.also { configuredClient ->
      client = configuredClient
    }
    return fixture
  }

  private fun validCreationRequest(): XoboroUserCreationRequest =
    XoboroUserCreationRequest(
      email = "created@example.invalid",
      password = "created-password",
      roles = listOf("PAGE_STREAMING"),
      sharedLibraryIds = listOf("library-one"),
      sharesAllLibraries = false,
      restrictions = XoboroContentRestrictionsRequest(),
    )

  private fun validUpdateRequest(): XoboroUserUpdateRequest =
    XoboroUserUpdateRequest(
      roles = listOf("PAGE_STREAMING"),
      sharedLibraryIds = emptyList(),
      sharesAllLibraries = true,
      restrictions = XoboroContentRestrictionsRequest(),
    )

  private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  private fun HttpRequestBuilder.crossSite() {
    header(HttpHeaders.Origin, "https://cross-site.example.invalid")
    header("Sec-Fetch-Site", "cross-site")
  }

  private class Fixture {
    val userRepository = RecordingUserRepository()
    val apiKeyRepository = RecordingApiKeyRepository()
    private val sessionRepository = InMemoryUserSessionRepository()
    private val sessionSequence = AtomicInteger()
    private val userSequence = AtomicInteger(10)
    private val apiKeySequence = AtomicInteger()
    val invalidatedUserIds = mutableListOf<UserId>()
    val admin =
      syntheticUser(
        id = "admin-user",
        email = "admin@example.invalid",
        rawPassword = "admin-password",
        roles = setOf(UserRole.ADMIN, UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING),
      )
    val reader =
      syntheticUser(
        id = "reader-user",
        email = "reader@example.invalid",
        rawPassword = "reader-password",
        roles = setOf(UserRole.PAGE_STREAMING),
      )
    val sessions =
      UserSessionLifecycle(
        users = userRepository,
        sessions = sessionRepository,
        tokenEncoder = syntheticTokenEncoder,
        plainTokenFactory = { "session-token-${sessionSequence.incrementAndGet()}" },
        currentTimeMillis = { 2_000 },
        inactivityTimeoutMillis = 60_000,
      )
    val users =
      UserLifecycle(
        users = userRepository,
        passwordHasher = syntheticPasswordHasher,
        userIdFactory = { "created-user-${userSequence.incrementAndGet()}" },
        currentTimeMillis = { 2_000 },
        invalidateUserSessions = { userId ->
          invalidatedUserIds += userId
          sessionRepository.deleteByUserId(userId)
        },
      )
    val apiKeys =
      ApiKeyLifecycle(
        users = userRepository,
        apiKeys = apiKeyRepository,
        tokenEncoder = syntheticTokenEncoder,
        apiKeyIdFactory = { "api-key-${apiKeySequence.incrementAndGet()}" },
        plainTextKeyFactory = { "plain-api-key-${apiKeySequence.get() + 1}" },
        currentTimeMillis = { 2_000 },
      )

    init {
      userRepository.seed(admin)
      userRepository.seed(reader)
    }

    fun bearerToken(user: User): String = requireNotNull(sessions.create(user)).plainToken

    fun addSecondAdministrator(): User =
      syntheticUser(
        id = "second-admin",
        email = "second-admin@example.invalid",
        rawPassword = "second-admin-password",
        roles = setOf(UserRole.ADMIN),
      ).also(userRepository::seed)

    fun resetObservations() {
      userRepository.resetObservations()
      apiKeyRepository.resetObservations()
      invalidatedUserIds.clear()
    }
  }

  private class RecordingUserRepository : UserRepository {
    private val users = linkedMapOf<UserId, User>()
    var beforeFindById: ((UserId) -> Unit)? = null
    val findByIdCalls = mutableListOf<UserId>()
    val updatedIds = mutableListOf<UserId>()
    val deletedIds = mutableListOf<UserId>()
    var insertCount: Int = 0
      private set

    val writeCount: Int
      get() = insertCount + updatedIds.size + deletedIds.size

    override fun count(): Long = users.size.toLong()

    override fun findByIdOrNull(id: UserId): User? {
      findByIdCalls += id
      beforeFindById?.invoke(id)
      return users[id]
    }

    override fun findByEmailIgnoreCaseOrNull(email: String): User? =
      users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun findAll(): List<User> = users.values.toList()

    override fun insert(user: User) {
      if (findByEmailIgnoreCaseOrNull(user.email) != null) {
        throw UserEmailAlreadyExistsException(user.email)
      }
      insertCount++
      users[user.id] = user
    }

    override fun claimIfEmpty(user: User): Boolean {
      if (users.isNotEmpty()) return false
      insertCount++
      users[user.id] = user
      return true
    }

    override fun update(user: User) {
      updatedIds += user.id
      users[user.id] = user
    }

    override fun delete(id: UserId) {
      deletedIds += id
      users.remove(id)
    }

    fun seed(user: User) {
      users[user.id] = user
    }

    fun userOrNull(id: UserId): User? = users[id]

    fun resetObservations() {
      findByIdCalls.clear()
      updatedIds.clear()
      deletedIds.clear()
      insertCount = 0
    }
  }

  private class RecordingApiKeyRepository : ApiKeyRepository {
    private val keys = linkedMapOf<ApiKeyId, ApiKey>()
    val userIdCalls = mutableListOf<UserId>()
    var insertCount: Int = 0
      private set
    var deleteCount: Int = 0
      private set

    val writeCount: Int
      get() = insertCount + deleteCount

    override fun findByKeyHashOrNull(keyHash: String): ApiKey? =
      keys.values.firstOrNull { it.keyHash == keyHash }

    override fun findAllByUserId(userId: UserId): List<ApiKey> {
      userIdCalls += userId
      return keys.values.filter { it.userId == userId }
    }

    override fun existsByCommentIgnoreCase(
      userId: UserId,
      comment: String,
    ): Boolean {
      userIdCalls += userId
      return keys.values.any {
        it.userId == userId && it.comment.equals(comment, ignoreCase = true)
      }
    }

    override fun insert(apiKey: ApiKey) {
      if (findByKeyHashOrNull(apiKey.keyHash) != null) {
        throw ApiKeyHashAlreadyExistsException()
      }
      insertCount++
      keys[apiKey.id] = apiKey
    }

    override fun deleteByIdAndUserId(
      id: ApiKeyId,
      userId: UserId,
    ): Boolean {
      userIdCalls += userId
      val existing = keys[id]?.takeIf { it.userId == userId } ?: return false
      keys.remove(existing.id)
      deleteCount++
      return true
    }

    fun findByIdOrNull(id: ApiKeyId): ApiKey? = keys[id]

    fun resetObservations() {
      userIdCalls.clear()
      insertCount = 0
      deleteCount = 0
    }
  }

  private companion object {
    val syntheticPasswordHasher =
      object : PasswordHasher {
        override fun hash(rawPassword: String): String = "password-hash:$rawPassword"

        override fun matches(
          rawPassword: String,
          passwordHash: String,
        ): Boolean = passwordHash == hash(rawPassword)
      }

    val syntheticTokenEncoder = TokenEncoder { rawToken -> "token-digest:$rawToken" }

    fun syntheticUser(
      id: String,
      email: String,
      rawPassword: String,
      roles: Set<UserRole>,
    ): User =
      User(
        id = UserId(id),
        email = email,
        passwordHash = syntheticPasswordHasher.hash(rawPassword),
        roles = roles,
        sharedLibraryIds = setOf(LibraryId("library-one")),
        sharesAllLibraries = false,
        restrictions =
          ContentRestrictions(
            ageRestriction = AgeRestriction(18, RestrictionMode.EXCLUDE),
            labelsAllow = setOf("synthetic allowed"),
            labelsExclude = setOf("synthetic excluded"),
          ),
        createdAtMillis = 1_000,
      )
  }
}
