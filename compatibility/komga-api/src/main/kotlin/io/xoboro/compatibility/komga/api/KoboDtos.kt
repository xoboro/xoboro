package io.xoboro.compatibility.komga.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KoboAuthDto(
  @SerialName("AccessToken")
  val accessToken: String,
  @SerialName("RefreshToken")
  val refreshToken: String,
  @SerialName("TokenType")
  val tokenType: String = "Bearer",
  @SerialName("TrackingId")
  val trackingId: String,
  @SerialName("UserKey")
  val userKey: String,
)

@Serializable
data class KoboDeviceAuthRequestDto(
  @SerialName("UserKey")
  val userKey: String = "",
)

@Serializable
data class KoboReadingStateUpdateDto(
  @SerialName("ReadingStates")
  val readingStates: List<KoboReadingStateDto> = emptyList(),
)

@Serializable
data class KoboReadingStateDto(
  @SerialName("Created")
  val created: String? = null,
  @SerialName("CurrentBookmark")
  val currentBookmark: KoboBookmarkDto,
  @SerialName("EntitlementId")
  val entitlementId: String,
  @SerialName("LastModified")
  val lastModified: String,
  @SerialName("PriorityTimestamp")
  val priorityTimestamp: String? = null,
  @SerialName("Statistics")
  val statistics: KoboStatisticsDto,
  @SerialName("StatusInfo")
  val statusInfo: KoboStatusInfoDto,
)

@Serializable
data class KoboBookmarkDto(
  @SerialName("LastModified")
  val lastModified: String,
  @SerialName("ProgressPercent")
  val progressPercent: Float? = null,
  @SerialName("ContentSourceProgressPercent")
  val contentSourceProgressPercent: Float? = null,
  @SerialName("Location")
  val location: KoboLocationDto? = null,
)

@Serializable
data class KoboLocationDto(
  @SerialName("Value")
  val value: String? = null,
  @SerialName("Type")
  val type: String? = "KoboSpan",
  @SerialName("Source")
  val source: String,
)

@Serializable
data class KoboStatisticsDto(
  @SerialName("LastModified")
  val lastModified: String,
  @SerialName("RemainingTimeMinutes")
  val remainingTimeMinutes: Int? = null,
  @SerialName("SpentReadingMinutes")
  val spentReadingMinutes: Int? = null,
)

@Serializable
data class KoboStatusInfoDto(
  @SerialName("LastModified")
  val lastModified: String,
  @SerialName("Status")
  val status: KoboStatusDto,
  @SerialName("TimesStartedReading")
  val timesStartedReading: Int? = null,
  @SerialName("LastTimeFinished")
  val lastTimeFinished: String? = null,
  @SerialName("LastTimeStartedReading")
  val lastTimeStartedReading: String? = null,
)

@Serializable
enum class KoboStatusDto {
  @SerialName("ReadyToRead")
  READY_TO_READ,

  @SerialName("Finished")
  FINISHED,

  @SerialName("Reading")
  READING,
}

@Serializable
data class KoboRequestResultDto(
  @SerialName("RequestResult")
  val requestResult: KoboResultDto,
  @SerialName("UpdateResults")
  val updateResults: List<KoboReadingStateUpdateResultDto>,
)

@Serializable
data class KoboReadingStateUpdateResultDto(
  @SerialName("EntitlementId")
  val entitlementId: String,
  @SerialName("CurrentBookmarkResult")
  val currentBookmarkResult: KoboWrappedResultDto,
  @SerialName("StatisticsResult")
  val statisticsResult: KoboWrappedResultDto,
  @SerialName("StatusInfoResult")
  val statusInfoResult: KoboWrappedResultDto,
)

@Serializable
data class KoboWrappedResultDto(
  @SerialName("Result")
  val result: KoboResultDto,
)

@Serializable
enum class KoboResultDto {
  @SerialName("Success")
  SUCCESS,

  @SerialName("Failure")
  FAILURE,

  @SerialName("Ignored")
  IGNORED,
}

@Serializable
data class KomgaKoboSyncToken(
  val version: Int = 1,
  val rawKoboSyncToken: String = "",
  val ongoingSyncPointId: String? = null,
  val lastSuccessfulSyncPointId: String? = null,
)
