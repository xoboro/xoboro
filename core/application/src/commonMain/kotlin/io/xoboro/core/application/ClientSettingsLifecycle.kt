package io.xoboro.core.application

import io.xoboro.core.domain.ClientSetting
import io.xoboro.core.domain.ClientSettingsRepository
import io.xoboro.core.domain.UserId

class ClientSettingsLifecycle(
  private val settings: ClientSettingsRepository,
) {
  fun findGlobal(onlyUnauthorized: Boolean = false): Map<String, ClientSetting> =
    settings.findGlobal(onlyUnauthorized)

  fun findForUser(userId: UserId): Map<String, ClientSetting> =
    settings.findForUser(userId)

  fun saveGlobal(newSettings: Map<String, ClientSetting>) {
    validate(newSettings)
    require(newSettings.values.all { it.allowUnauthorized != null }) {
      "Global client settings require allowUnauthorized"
    }
    settings.saveGlobal(newSettings)
  }

  fun saveForUser(
    userId: UserId,
    newSettings: Map<String, ClientSetting>,
  ) {
    validate(newSettings)
    settings.saveForUser(
      userId,
      newSettings.mapValues { (_, setting) -> setting.copy(allowUnauthorized = null) },
    )
  }

  fun deleteGlobal(keys: Set<String>) {
    validateKeys(keys)
    settings.deleteGlobal(keys)
  }

  fun deleteForUser(
    userId: UserId,
    keys: Set<String>,
  ) {
    validateKeys(keys)
    settings.deleteForUser(userId, keys)
  }

  private fun validate(values: Map<String, ClientSetting>) {
    validateKeys(values.keys)
    require(values.values.none { it.value.isBlank() }) {
      "Client setting value must not be blank"
    }
  }

  private fun validateKeys(keys: Set<String>) {
    require(keys.all(KEY_PATTERN::matches)) {
      "Client setting key must be a lowercase namespace"
    }
  }

  companion object {
    val KEY_PATTERN =
      Regex("""^[a-z](?:[a-z0-9_-]*[a-z0-9])*(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])*)*$""")
  }
}
