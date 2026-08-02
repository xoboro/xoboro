package io.xoboro.server.sources.webdav

import io.xoboro.core.application.RootType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavLibraryRootInspectorTest {
  @Test
  fun `reports a collection root as DIRECTORY`() {
    FakeWebDavServer(FixtureDirectory("dav")).use { server ->
      assertEquals(RootType.DIRECTORY, WebDavLibraryRootInspector().typeOf(server.baseUrl))
    }
  }

  @Test
  fun `reports a non-existent root as MISSING rather than throwing`() {
    FakeWebDavServer(FixtureDirectory("dav")).use { server ->
      assertEquals(RootType.MISSING, WebDavLibraryRootInspector().typeOf("${server.baseUrl}/nope"))
    }
  }

  @Test
  fun `a wrong or missing credential makes the root unreadable, not an exception`() {
    val credentials = WebDavCredentials("alice", "hunter2")
    FakeWebDavServer(FixtureDirectory("dav"), credentials).use { server ->
      val inspector = WebDavLibraryRootInspector(environment = emptyMap())
      assertFalse(inspector.isReadable(server.baseUrl))
      assertEquals(RootType.MISSING, inspector.typeOf(server.baseUrl))
    }
  }

  @Test
  fun `a matching per-library credential id makes an authenticated root readable`() {
    val credentials = WebDavCredentials("alice", "hunter2")
    FakeWebDavServer(FixtureDirectory("dav"), credentials).use { server ->
      val environment = mapOf("XOBORO_WEBDAV_NAS1_USERNAME" to "alice", "XOBORO_WEBDAV_NAS1_PASSWORD" to "hunter2")
      val inspector = WebDavLibraryRootInspector(environment = environment)
      assertTrue(inspector.isReadable("${server.baseUrl}#nas1"))
      assertEquals(RootType.DIRECTORY, inspector.typeOf("${server.baseUrl}#nas1"))
    }
  }

  @Test
  fun `a shared root is its own ancestor, and an unrelated root is not`() {
    FakeWebDavServer(FixtureDirectory("dav", mutableListOf(FixtureDirectory("Sub")))).use { server ->
      val inspector = WebDavLibraryRootInspector()
      assertTrue(inspector.isSameOrAncestor(server.baseUrl, server.baseUrl))
      assertTrue(inspector.isSameOrAncestor(server.baseUrl, "${server.baseUrl}/Sub"))
      assertFalse(inspector.isSameOrAncestor("${server.baseUrl}/Sub", server.baseUrl))
      assertFalse(inspector.isSameOrAncestor(server.baseUrl, "http://127.0.0.1:1/unrelated"))
    }
  }
}
