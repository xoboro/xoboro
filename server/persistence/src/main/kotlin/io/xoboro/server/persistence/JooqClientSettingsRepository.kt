package io.xoboro.server.persistence

import io.xoboro.core.domain.ClientSetting
import io.xoboro.core.domain.ClientSettingsRepository
import io.xoboro.core.domain.UserId

class JooqClientSettingsRepository(
  private val database: XoboroDatabase,
) : ClientSettingsRepository {
  override fun findGlobal(onlyUnauthorized: Boolean): Map<String, ClientSetting> =
    database.dsl
      .fetch(
        """
        SELECT setting_key, setting_value, allow_unauthorized
        FROM client_setting_global
        ${if (onlyUnauthorized) "WHERE allow_unauthorized = 1" else ""}
        ORDER BY setting_key
        """.trimIndent(),
      ).associate { record ->
        requireNotNull(record.get("setting_key", String::class.java)) to
          ClientSetting(
            value = requireNotNull(record.get("setting_value", String::class.java)),
            allowUnauthorized = record.get("allow_unauthorized", Int::class.java) == 1,
          )
      }

  override fun findForUser(userId: UserId): Map<String, ClientSetting> =
    database.dsl
      .fetch(
        """
        SELECT setting_key, setting_value
        FROM client_setting_user
        WHERE user_id = ?
        ORDER BY setting_key
        """.trimIndent(),
        userId.value,
      ).associate { record ->
        requireNotNull(record.get("setting_key", String::class.java)) to
          ClientSetting(
            value = requireNotNull(record.get("setting_value", String::class.java)),
          )
      }

  override fun saveGlobal(settings: Map<String, ClientSetting>) {
    database.transaction { transaction ->
      settings.forEach { (key, setting) ->
        transaction.execute(
          """
          INSERT INTO client_setting_global
            (setting_key, setting_value, allow_unauthorized)
          VALUES (?, ?, ?)
          ON CONFLICT(setting_key) DO UPDATE SET
            setting_value = excluded.setting_value,
            allow_unauthorized = excluded.allow_unauthorized
          """.trimIndent(),
          key,
          setting.value,
          if (setting.allowUnauthorized == true) 1 else 0,
        )
      }
    }
  }

  override fun saveForUser(
    userId: UserId,
    settings: Map<String, ClientSetting>,
  ) {
    database.transaction { transaction ->
      settings.forEach { (key, setting) ->
        transaction.execute(
          """
          INSERT INTO client_setting_user (user_id, setting_key, setting_value)
          VALUES (?, ?, ?)
          ON CONFLICT(user_id, setting_key) DO UPDATE SET
            setting_value = excluded.setting_value
          """.trimIndent(),
          userId.value,
          key,
          setting.value,
        )
      }
    }
  }

  override fun deleteGlobal(keys: Set<String>) {
    if (keys.isEmpty()) return
    val placeholders = keys.joinToString(",") { "?" }
    database.dsl.execute(
      "DELETE FROM client_setting_global WHERE setting_key IN ($placeholders)",
      *keys.toTypedArray(),
    )
  }

  override fun deleteForUser(
    userId: UserId,
    keys: Set<String>,
  ) {
    if (keys.isEmpty()) return
    val placeholders = keys.joinToString(",") { "?" }
    database.dsl.execute(
      """
      DELETE FROM client_setting_user
      WHERE user_id = ? AND setting_key IN ($placeholders)
      """.trimIndent(),
      userId.value,
      *keys.toTypedArray(),
    )
  }
}
