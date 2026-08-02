package io.xoboro.server.sources.webdav

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebDavCredentialsResolverTest {
  @Test
  fun `returns null when no credential variables are set`() {
    assertNull(WebDavCredentialsResolver.resolve(credentialId = null, environment = emptyMap()))
  }

  @Test
  fun `resolves the unsuffixed default when no credential id is given`() {
    val environment =
      mapOf(
        "XOBORO_WEBDAV_USERNAME" to "alice",
        "XOBORO_WEBDAV_PASSWORD" to "hunter2",
      )
    val credentials = WebDavCredentialsResolver.resolve(credentialId = null, environment = environment)
    assertEquals(WebDavCredentials("alice", "hunter2"), credentials)
  }

  @Test
  fun `a per-library credential id overrides the default when its own variables are set`() {
    val environment =
      mapOf(
        "XOBORO_WEBDAV_USERNAME" to "default-user",
        "XOBORO_WEBDAV_PASSWORD" to "default-pass",
        "XOBORO_WEBDAV_NAS1_USERNAME" to "nas1-user",
        "XOBORO_WEBDAV_NAS1_PASSWORD" to "nas1-pass",
      )
    val credentials = WebDavCredentialsResolver.resolve(credentialId = "nas1", environment = environment)
    assertEquals(WebDavCredentials("nas1-user", "nas1-pass"), credentials)
  }

  @Test
  fun `a credential id without its own variables falls back to the default`() {
    val environment =
      mapOf(
        "XOBORO_WEBDAV_USERNAME" to "default-user",
        "XOBORO_WEBDAV_PASSWORD" to "default-pass",
      )
    val credentials = WebDavCredentialsResolver.resolve(credentialId = "nas1", environment = environment)
    assertEquals(WebDavCredentials("default-user", "default-pass"), credentials)
  }

  @Test
  fun `sanitizes a credential id into a safe environment-variable suffix`() {
    val environment =
      mapOf(
        "XOBORO_WEBDAV_MY_NAS_1_USERNAME" to "user",
        "XOBORO_WEBDAV_MY_NAS_1_PASSWORD" to "pass",
      )
    val credentials = WebDavCredentialsResolver.resolve(credentialId = "my-nas.1", environment = environment)
    assertEquals(WebDavCredentials("user", "pass"), credentials)
  }

  @Test
  fun `rejects a username set without a matching password`() {
    val environment = mapOf("XOBORO_WEBDAV_USERNAME" to "alice")
    assertFailsWith<IllegalArgumentException> {
      WebDavCredentialsResolver.resolve(credentialId = null, environment = environment)
    }
  }

  @Test
  fun `never renders the password through toString`() {
    val credentials = WebDavCredentials("alice", "hunter2")
    assertEquals("WebDavCredentials(username=alice, password=***)", credentials.toString())
  }

  @Test
  fun `never mixes a per-library username with the default password`() {
    // Each half used to fall back on its own, so a library that set only its own username
    // would be sent that username with somebody else's password. The server answers 401 and
    // nothing in the message says the two halves came from different places.
    val failure =
      assertFailsWith<IllegalArgumentException> {
        WebDavCredentialsResolver.resolve(
          credentialId = "nas1",
          environment =
            mapOf(
              "XOBORO_WEBDAV_NAS1_USERNAME" to "library-user",
              "XOBORO_WEBDAV_USERNAME" to "default-user",
              "XOBORO_WEBDAV_PASSWORD" to "default-password",
            ),
        )
      }

    // Named, so an operator can see which half they forgot rather than guessing at a 401.
    assertTrue(failure.message.orEmpty().contains("XOBORO_WEBDAV_NAS1"))
  }
}
