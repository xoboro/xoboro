package io.xoboro.server.persistence

class JooqServerSettingRepository(
  private val database: XoboroDatabase,
) {
  fun findOrCreate(
    key: String,
    valueFactory: () -> String,
  ): String {
    require(key.isNotBlank()) { "Server setting key must not be blank" }
    return database.transaction { transaction ->
      transaction
        .fetchOne(
          "SELECT setting_value FROM server_setting WHERE setting_key = ?",
          key,
        )?.get("setting_value", String::class.java)
        ?: valueFactory().also { value ->
          require(value.isNotBlank()) { "Server setting value must not be blank" }
          transaction.execute(
            """
            INSERT OR IGNORE INTO server_setting (setting_key, setting_value)
            VALUES (?, ?)
            """.trimIndent(),
            key,
            value,
          )
        }.let {
          requireNotNull(
            transaction
              .fetchOne(
                "SELECT setting_value FROM server_setting WHERE setting_key = ?",
                key,
              )?.get("setting_value", String::class.java),
          )
        }
    }
  }

  fun put(
    key: String,
    value: String,
  ) {
    require(key.isNotBlank()) { "Server setting key must not be blank" }
    require(value.isNotBlank()) { "Server setting value must not be blank" }
    database.dsl.execute(
      """
      INSERT INTO server_setting (setting_key, setting_value)
      VALUES (?, ?)
      ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
      """.trimIndent(),
      key,
      value,
    )
  }
}
