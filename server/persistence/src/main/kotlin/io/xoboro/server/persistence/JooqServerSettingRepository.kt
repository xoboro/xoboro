package io.xoboro.server.persistence

import io.xoboro.core.application.ServerSettingStore

class JooqServerSettingRepository(
  private val database: XoboroDatabase,
) : ServerSettingStore {
  override fun find(key: String): String? {
    require(key.isNotBlank()) { "Server setting key must not be blank" }
    return database.dsl
      .fetchOne(
        "SELECT setting_value FROM server_setting WHERE setting_key = ?",
        key,
      )?.get("setting_value", String::class.java)
  }

  /**
   * Returns the stored value for [key], creating it from [valueFactory] if absent.
   *
   * Deliberately uses no transaction. The previous implementation wrapped a `SELECT`, a conditional
   * `INSERT OR IGNORE` and a re-`SELECT` in one transaction, which is the read-then-write shape that
   * fails with `SQLITE_BUSY_SNAPSHOT`: SQLite refuses to upgrade a transaction to a writer once
   * another connection has committed against the read snapshot it already took, and `busy_timeout`
   * does not wait for a mid-transaction lock upgrade the way it waits for a fresh transaction's
   * first write.
   *
   * The transaction bought nothing here. `INSERT OR IGNORE` is atomic on its own, and it is the
   * read-back after it - not the transaction - that makes concurrent callers agree: two callers can
   * both find the key absent and both run [valueFactory], the first writer wins, and the read-back
   * returns that same winner to everyone. That was already true of the transactional version, whose
   * deferred read snapshot could likewise miss a concurrent insert.
   */
  override fun findOrCreate(
    key: String,
    valueFactory: () -> String,
  ): String {
    require(key.isNotBlank()) { "Server setting key must not be blank" }
    find(key)?.let { return it }
    val value = valueFactory()
    require(value.isNotBlank()) { "Server setting value must not be blank" }
    database.dsl.execute(
      """
      INSERT OR IGNORE INTO server_setting (setting_key, setting_value)
      VALUES (?, ?)
      """.trimIndent(),
      key,
      value,
    )
    return requireNotNull(find(key)) { "Server setting $key vanished immediately after creation" }
  }

  override fun put(
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

  override fun delete(key: String) {
    require(key.isNotBlank()) { "Server setting key must not be blank" }
    database.dsl.execute(
      "DELETE FROM server_setting WHERE setting_key = ?",
      key,
    )
  }
}
