package io.xoboro.core.domain

data class ClientSetting(
  val value: String,
  val allowUnauthorized: Boolean? = null,
) {
  init {
    require(value.isNotBlank()) { "Client setting value must not be blank" }
  }
}

interface ClientSettingsRepository {
  fun findGlobal(onlyUnauthorized: Boolean = false): Map<String, ClientSetting>

  fun findForUser(userId: UserId): Map<String, ClientSetting>

  fun saveGlobal(settings: Map<String, ClientSetting>)

  fun saveForUser(
    userId: UserId,
    settings: Map<String, ClientSetting>,
  )

  fun deleteGlobal(keys: Set<String>)

  fun deleteForUser(
    userId: UserId,
    keys: Set<String>,
  )
}
