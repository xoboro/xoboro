package io.xoboro.core.application

import io.xoboro.core.domain.ClientSetting
import io.xoboro.core.domain.ClientSettingsRepository
import io.xoboro.core.domain.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientSettingsLifecycleTest {
  @Test
  fun `validates namespaced keys and normalizes user settings`() {
    val repository = InMemoryClientSettings()
    val lifecycle = ClientSettingsLifecycle(repository)
    val userId = UserId("user-1")

    lifecycle.saveGlobal(
      mapOf("application.reader_theme" to ClientSetting("dark", allowUnauthorized = true)),
    )
    lifecycle.saveForUser(
      userId,
      mapOf("reader.page-layout" to ClientSetting("paged", allowUnauthorized = true)),
    )

    assertEquals(true, lifecycle.findGlobal().getValue("application.reader_theme").allowUnauthorized)
    assertEquals(null, lifecycle.findForUser(userId).getValue("reader.page-layout").allowUnauthorized)
    assertFailsWith<IllegalArgumentException> {
      lifecycle.saveGlobal(
        mapOf("Invalid Key" to ClientSetting("unused", allowUnauthorized = false)),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      lifecycle.deleteForUser(userId, setOf(".invalid"))
    }
  }

  private class InMemoryClientSettings : ClientSettingsRepository {
    private val global = linkedMapOf<String, ClientSetting>()
    private val users = linkedMapOf<UserId, MutableMap<String, ClientSetting>>()

    override fun findGlobal(onlyUnauthorized: Boolean): Map<String, ClientSetting> =
      global.filterValues { !onlyUnauthorized || it.allowUnauthorized == true }

    override fun findForUser(userId: UserId): Map<String, ClientSetting> =
      users[userId].orEmpty()

    override fun saveGlobal(settings: Map<String, ClientSetting>) {
      global.putAll(settings)
    }

    override fun saveForUser(
      userId: UserId,
      settings: Map<String, ClientSetting>,
    ) {
      users.getOrPut(userId, ::linkedMapOf).putAll(settings)
    }

    override fun deleteGlobal(keys: Set<String>) {
      keys.forEach(global::remove)
    }

    override fun deleteForUser(
      userId: UserId,
      keys: Set<String>,
    ) {
      users[userId]?.let { values ->
        keys.forEach(values::remove)
      }
    }
  }
}
