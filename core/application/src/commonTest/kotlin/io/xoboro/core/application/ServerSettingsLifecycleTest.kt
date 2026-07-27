package io.xoboro.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerSettingsLifecycleTest {
  @Test
  fun `provides defaults and creates a durable remember me key`() {
    val store = InMemoryServerSettingStore()
    val lifecycle = lifecycle(store)

    assertEquals("synthetic-key-1", lifecycle.rememberMeKey())
    assertEquals(ServerSettingsLifecycle.DEFAULT_REMEMBER_ME_DURATION_DAYS, lifecycle.snapshot().rememberMeDurationDays)
    assertEquals(3, lifecycle.snapshot().taskPoolSize)
    assertEquals(ThumbnailSize.DEFAULT, lifecycle.snapshot().thumbnailSize)
    assertEquals(false, lifecycle.snapshot().deleteEmptyCollections)
    assertEquals(false, lifecycle.snapshot().deleteEmptyReadLists)
    assertEquals(false, lifecycle.snapshot().koboProxy)
  }

  @Test
  fun `updates every mutable setting and reports all sources`() {
    val store = InMemoryServerSettingStore()
    val resizedTo = mutableListOf<Int>()
    val lifecycle =
      lifecycle(
        store = store,
        onTaskPoolSizeChanged = resizedTo::add,
      )

    lifecycle.update(
      ServerSettingsUpdate(
        deleteEmptyCollections = true,
        deleteEmptyReadLists = true,
        rememberMeDurationDays = 14,
        renewRememberMeKey = true,
        thumbnailSize = ThumbnailSize.XLARGE,
        taskPoolSize = 7,
        serverPort = NullableSettingUpdate(isSet = true, value = 9_001),
        serverContextPath = NullableSettingUpdate(isSet = true, value = "/reader"),
        koboProxy = true,
        koboPort = NullableSettingUpdate(isSet = true, value = 9_002),
        kepubifyPath = NullableSettingUpdate(isSet = true, value = "/tools/kepubify"),
      ),
    )

    val snapshot = lifecycle.snapshot()
    assertEquals(true, snapshot.deleteEmptyCollections)
    assertEquals(true, snapshot.deleteEmptyReadLists)
    assertEquals(14, snapshot.rememberMeDurationDays)
    assertEquals(14L * 86_400_000L, lifecycle.rememberMeDurationMillis())
    assertEquals(14 * 86_400, lifecycle.rememberMeMaxAgeSeconds())
    assertEquals("synthetic-key-2", lifecycle.rememberMeKey())
    assertEquals(ThumbnailSize.XLARGE, snapshot.thumbnailSize)
    assertEquals(7, snapshot.taskPoolSize)
    assertEquals(9_001, snapshot.serverPort.databaseSource)
    assertEquals(8_080, snapshot.serverPort.configurationSource)
    assertEquals(8_081, snapshot.serverPort.effectiveValue)
    assertEquals("/reader", snapshot.serverContextPath.databaseSource)
    assertEquals("/configured", snapshot.serverContextPath.configurationSource)
    assertEquals("/effective", snapshot.serverContextPath.effectiveValue)
    assertEquals(true, snapshot.koboProxy)
    assertEquals(9_002, snapshot.koboPort)
    assertEquals("/tools/kepubify", snapshot.kepubifyPath.databaseSource)
    assertEquals("/configured/kepubify", snapshot.kepubifyPath.configurationSource)
    assertEquals("/effective/kepubify", snapshot.kepubifyPath.effectiveValue)
    assertEquals(listOf(7), resizedTo)
  }

  @Test
  fun `explicit null deletes nullable database settings`() {
    val store = InMemoryServerSettingStore()
    val lifecycle = lifecycle(store)
    lifecycle.update(
      ServerSettingsUpdate(
        serverPort = NullableSettingUpdate(true, 9_001),
        serverContextPath = NullableSettingUpdate(true, "/reader"),
        koboPort = NullableSettingUpdate(true, 9_002),
        kepubifyPath = NullableSettingUpdate(true, "/tools/kepubify"),
      ),
    )

    lifecycle.update(
      ServerSettingsUpdate(
        serverPort = NullableSettingUpdate(isSet = true),
        serverContextPath = NullableSettingUpdate(isSet = true),
        koboPort = NullableSettingUpdate(isSet = true),
        kepubifyPath = NullableSettingUpdate(isSet = true),
      ),
    )

    assertNull(lifecycle.snapshot().serverPort.databaseSource)
    assertNull(lifecycle.snapshot().serverContextPath.databaseSource)
    assertNull(lifecycle.snapshot().koboPort)
    assertNull(lifecycle.snapshot().kepubifyPath.databaseSource)
  }

  @Test
  fun `rejects invalid settings before writing`() {
    val store = InMemoryServerSettingStore()
    val lifecycle = lifecycle(store)

    listOf(
      ServerSettingsUpdate(rememberMeDurationDays = 0),
      ServerSettingsUpdate(taskPoolSize = 0),
      ServerSettingsUpdate(serverPort = NullableSettingUpdate(true, 0)),
      ServerSettingsUpdate(koboPort = NullableSettingUpdate(true, 65_536)),
      ServerSettingsUpdate(serverContextPath = NullableSettingUpdate(true, "reader")),
    ).forEach { update ->
      assertFailsWith<IllegalArgumentException> { lifecycle.update(update) }
    }
    assertEquals(1, store.values.size)
  }

  private fun lifecycle(
    store: InMemoryServerSettingStore,
    onTaskPoolSizeChanged: (Int) -> Unit = {},
  ): ServerSettingsLifecycle {
    var sequence = 0
    return ServerSettingsLifecycle(
      store = store,
      configuredServerPort = 8_080,
      effectiveServerPort = { 8_081 },
      configuredServerContextPath = "/configured",
      effectiveServerContextPath = { "/effective" },
      configuredKepubifyPath = "/configured/kepubify",
      effectiveKepubifyPath = { "/effective/kepubify" },
      defaultTaskPoolSize = 3,
      rememberMeKeyFactory = {
        sequence += 1
        "synthetic-key-$sequence"
      },
      onTaskPoolSizeChanged = onTaskPoolSizeChanged,
    )
  }

  private class InMemoryServerSettingStore : ServerSettingStore {
    val values = mutableMapOf<String, String>()

    override fun find(key: String): String? = values[key]

    override fun findOrCreate(
      key: String,
      valueFactory: () -> String,
    ): String = values.getOrPut(key, valueFactory)

    override fun put(
      key: String,
      value: String,
    ) {
      values[key] = value
    }

    override fun delete(key: String) {
      values.remove(key)
    }
  }
}
