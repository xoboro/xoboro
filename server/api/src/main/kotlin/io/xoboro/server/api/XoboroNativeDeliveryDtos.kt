package io.xoboro.server.api

import kotlinx.serialization.Serializable

@Serializable
data class XoboroMediaPageResponse(
  val number: Int,
  val mediaType: String,
  val width: Int? = null,
  val height: Int? = null,
  val sizeBytes: Long? = null,
)

@Serializable
data class XoboroResourceResponse(
  val path: String,
  val mediaType: String? = null,
  val sizeBytes: Long? = null,
  val kind: String,
)
