package io.xoboro.server.api

import kotlinx.serialization.Serializable

@Serializable
data class XoboroCollectionResponse(
  val id: String,
  val name: String,
  val ordered: Boolean,
  val memberCount: Int,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroReadListResponse(
  val id: String,
  val name: String,
  val summary: String,
  val ordered: Boolean,
  val memberCount: Int,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Serializable
data class XoboroCollectionCreationRequest(
  val name: String,
  val ordered: Boolean = true,
  val seriesIds: List<String>,
)

@Serializable
data class XoboroCollectionUpdateRequest(
  val name: String,
  val ordered: Boolean,
  val seriesIds: List<String>,
)

@Serializable
data class XoboroReadListCreationRequest(
  val name: String,
  val summary: String = "",
  val ordered: Boolean = true,
  val mediaItemIds: List<String>,
)

@Serializable
data class XoboroReadListUpdateRequest(
  val name: String,
  val summary: String,
  val ordered: Boolean,
  val mediaItemIds: List<String>,
)
