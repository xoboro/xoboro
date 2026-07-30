package io.xoboro.core.application

enum class ThumbnailSize {
  DEFAULT,
  MEDIUM,
  LARGE,
  XLARGE,
}

data class SettingMultiSource<T>(
  val configurationSource: T,
  val databaseSource: T,
  val effectiveValue: T,
)

data class ServerSettingsSnapshot(
  val deleteEmptyCollections: Boolean,
  val deleteEmptyReadLists: Boolean,
  val rememberMeDurationDays: Long,
  val thumbnailSize: ThumbnailSize,
  val taskPoolSize: Int,
  val serverPort: SettingMultiSource<Int?>,
  val serverContextPath: SettingMultiSource<String?>,
  val koboProxy: Boolean,
  val koboPort: Int?,
  val kepubifyPath: SettingMultiSource<String?>,
  val historyRetentionDays: Long,
  val authenticationActivityRetentionDays: Long,
)

data class NullableSettingUpdate<T>(
  val isSet: Boolean = false,
  val value: T? = null,
)

data class ServerSettingsUpdate(
  val deleteEmptyCollections: Boolean? = null,
  val deleteEmptyReadLists: Boolean? = null,
  val rememberMeDurationDays: Long? = null,
  val renewRememberMeKey: Boolean? = null,
  val thumbnailSize: ThumbnailSize? = null,
  val taskPoolSize: Int? = null,
  val serverPort: NullableSettingUpdate<Int> = NullableSettingUpdate(),
  val serverContextPath: NullableSettingUpdate<String> = NullableSettingUpdate(),
  val koboProxy: Boolean? = null,
  val koboPort: NullableSettingUpdate<Int> = NullableSettingUpdate(),
  val kepubifyPath: NullableSettingUpdate<String> = NullableSettingUpdate(),
  /** Days of catalog history to keep; `0` keeps everything. */
  val historyRetentionDays: Long? = null,
  /** Days of authentication activity to keep; `0` keeps everything. */
  val authenticationActivityRetentionDays: Long? = null,
)

interface ServerSettingStore {
  fun find(key: String): String?

  fun findOrCreate(
    key: String,
    valueFactory: () -> String,
  ): String

  fun put(
    key: String,
    value: String,
  )

  fun delete(key: String)
}

class ServerSettingsLifecycle(
  private val store: ServerSettingStore,
  private val configuredServerPort: Int?,
  private val effectiveServerPort: () -> Int,
  private val configuredServerContextPath: String? = null,
  private val effectiveServerContextPath: () -> String? = { null },
  private val configuredKepubifyPath: String? = null,
  private val effectiveKepubifyPath: () -> String? = { null },
  private val defaultTaskPoolSize: Int = 1,
  private val rememberMeKeyFactory: () -> String,
  private val onTaskPoolSizeChanged: (Int) -> Unit = {},
) {
  init {
    require(defaultTaskPoolSize in 1..64) { "Default task pool size must be between 1 and 64" }
    store.findOrCreate(REMEMBER_ME_KEY) {
      rememberMeKeyFactory().also {
        require(it.isNotBlank()) { "Remember-me key must not be blank" }
      }
    }
  }

  fun snapshot(): ServerSettingsSnapshot {
    val databasePort = store.find(SERVER_PORT)?.toInt()
    val databaseContextPath = store.find(SERVER_CONTEXT_PATH)
    val databaseKepubifyPath = store.find(KEPUBIFY_PATH)
    return ServerSettingsSnapshot(
      deleteEmptyCollections = boolean(DELETE_EMPTY_COLLECTIONS, false),
      deleteEmptyReadLists = boolean(DELETE_EMPTY_READ_LISTS, false),
      rememberMeDurationDays = long(REMEMBER_ME_DURATION, DEFAULT_REMEMBER_ME_DURATION_DAYS),
      thumbnailSize =
        store.find(THUMBNAIL_SIZE)?.let(ThumbnailSize::valueOf) ?: ThumbnailSize.DEFAULT,
      taskPoolSize = int(TASK_POOL_SIZE, defaultTaskPoolSize),
      serverPort =
        SettingMultiSource(
          configurationSource = configuredServerPort,
          databaseSource = databasePort,
          effectiveValue = effectiveServerPort(),
        ),
      serverContextPath =
        SettingMultiSource(
          configurationSource = configuredServerContextPath,
          databaseSource = databaseContextPath,
          effectiveValue = effectiveServerContextPath(),
        ),
      koboProxy = boolean(KOBO_PROXY, false),
      koboPort = store.find(KOBO_PORT)?.toInt(),
      kepubifyPath =
        SettingMultiSource(
          configurationSource = configuredKepubifyPath,
          databaseSource = databaseKepubifyPath,
          effectiveValue = effectiveKepubifyPath(),
        ),
      historyRetentionDays = long(HISTORY_RETENTION_DAYS, ActivityRetention.KEEP_FOREVER),
      authenticationActivityRetentionDays =
        long(AUTHENTICATION_ACTIVITY_RETENTION_DAYS, ActivityRetention.KEEP_FOREVER),
    )
  }

  /**
   * The retention policy an [ActivityRetentionLifecycle] sweep should apply.
   *
   * Read per sweep rather than captured once, so a change through the settings API takes effect at the
   * next sweep instead of at the next restart.
   */
  fun activityRetention(): ActivityRetention =
    ActivityRetention(
      historyDays = long(HISTORY_RETENTION_DAYS, ActivityRetention.KEEP_FOREVER),
      authenticationActivityDays =
        long(AUTHENTICATION_ACTIVITY_RETENTION_DAYS, ActivityRetention.KEEP_FOREVER),
    )

  fun update(update: ServerSettingsUpdate) {
    validate(update)
    update.deleteEmptyCollections?.let { store.put(DELETE_EMPTY_COLLECTIONS, it.toString()) }
    update.deleteEmptyReadLists?.let { store.put(DELETE_EMPTY_READ_LISTS, it.toString()) }
    update.rememberMeDurationDays?.let { store.put(REMEMBER_ME_DURATION, it.toString()) }
    if (update.renewRememberMeKey == true) {
      store.put(
        REMEMBER_ME_KEY,
        rememberMeKeyFactory().also {
          require(it.isNotBlank()) { "Remember-me key must not be blank" }
        },
      )
    }
    update.thumbnailSize?.let { store.put(THUMBNAIL_SIZE, it.name) }
    update.taskPoolSize?.let {
      store.put(TASK_POOL_SIZE, it.toString())
      onTaskPoolSizeChanged(it)
    }
    update.serverPort.persistNullable(SERVER_PORT, Int::toString)
    update.serverContextPath.persistNullable(SERVER_CONTEXT_PATH)
    update.koboProxy?.let { store.put(KOBO_PROXY, it.toString()) }
    update.koboPort.persistNullable(KOBO_PORT, Int::toString)
    update.kepubifyPath.persistNullable(KEPUBIFY_PATH)
    update.historyRetentionDays?.let { store.put(HISTORY_RETENTION_DAYS, it.toString()) }
    update.authenticationActivityRetentionDays?.let {
      store.put(AUTHENTICATION_ACTIVITY_RETENTION_DAYS, it.toString())
    }
  }

  fun rememberMeKey(): String =
    requireNotNull(store.find(REMEMBER_ME_KEY))

  fun rememberMeDurationMillis(): Long =
    long(REMEMBER_ME_DURATION, DEFAULT_REMEMBER_ME_DURATION_DAYS) * MILLIS_PER_DAY

  fun rememberMeMaxAgeSeconds(): Int =
    (long(REMEMBER_ME_DURATION, DEFAULT_REMEMBER_ME_DURATION_DAYS) * SECONDS_PER_DAY)
      .toInt()

  private fun validate(update: ServerSettingsUpdate) {
    update.rememberMeDurationDays?.let {
      require(it in 1..MAX_REMEMBER_ME_DURATION_DAYS) {
        "Remember-me duration must be between 1 and $MAX_REMEMBER_ME_DURATION_DAYS days"
      }
    }
    update.taskPoolSize?.let {
      require(it in 1..64) { "Task pool size must be between 1 and 64" }
    }
    update.serverPort.value?.let {
      require(it in 1..65_535) { "Server port must be between 1 and 65535" }
    }
    update.koboPort.value?.let {
      require(it in 1..65_535) { "Kobo port must be between 1 and 65535" }
    }
    update.serverContextPath.value?.let {
      require(CONTEXT_PATH_PATTERN.matches(it)) { "Server context path is invalid" }
    }
    // Zero is valid and means "keep forever"; negative is not, because it would silently read as a
    // cutoff in the future and delete everything.
    update.historyRetentionDays?.let {
      require(it >= 0) { "History retention must not be negative" }
    }
    update.authenticationActivityRetentionDays?.let {
      require(it >= 0) { "Authentication activity retention must not be negative" }
    }
  }

  private fun boolean(
    key: String,
    default: Boolean,
  ): Boolean = store.find(key)?.toBooleanStrict() ?: default

  private fun int(
    key: String,
    default: Int,
  ): Int = store.find(key)?.toInt() ?: default

  private fun long(
    key: String,
    default: Long,
  ): Long = store.find(key)?.toLong() ?: default

  private fun NullableSettingUpdate<String>.persistNullable(key: String) {
    if (!isSet) return
    value?.let { store.put(key, it) } ?: store.delete(key)
  }

  private fun <T> NullableSettingUpdate<T>.persistNullable(
    key: String,
    transform: (T) -> String,
  ) {
    if (!isSet) return
    value?.let { store.put(key, transform(it)) } ?: store.delete(key)
  }

  companion object {
    const val DEFAULT_REMEMBER_ME_DURATION_DAYS: Long = 365
    const val MAX_REMEMBER_ME_DURATION_DAYS: Long = Int.MAX_VALUE / 86_400L
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val SECONDS_PER_DAY = 86_400L
    private const val DELETE_EMPTY_COLLECTIONS = "DELETE_EMPTY_COLLECTIONS"
    private const val DELETE_EMPTY_READ_LISTS = "DELETE_EMPTY_READLISTS"
    private const val REMEMBER_ME_KEY = "REMEMBER_ME_KEY"
    private const val REMEMBER_ME_DURATION = "REMEMBER_ME_DURATION"
    private const val THUMBNAIL_SIZE = "THUMBNAIL_SIZE"
    private const val TASK_POOL_SIZE = "TASK_POOL_SIZE"
    private const val SERVER_PORT = "SERVER_PORT"
    private const val SERVER_CONTEXT_PATH = "SERVER_CONTEXT_PATH"
    private const val KOBO_PROXY = "KOBO_PROXY"
    private const val KOBO_PORT = "KOBO_PORT"
    private const val KEPUBIFY_PATH = "KEPUBIFY_PATH"
    private const val HISTORY_RETENTION_DAYS = "HISTORY_RETENTION_DAYS"
    private const val AUTHENTICATION_ACTIVITY_RETENTION_DAYS =
      "AUTHENTICATION_ACTIVITY_RETENTION_DAYS"
    private val CONTEXT_PATH_PATTERN = Regex("^/[\\w-/]*[a-zA-Z0-9]$")
  }
}
