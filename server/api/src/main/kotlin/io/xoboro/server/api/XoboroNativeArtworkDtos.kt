package io.xoboro.server.api

import io.xoboro.core.domain.Artwork
import kotlinx.serialization.Serializable

@Serializable
data class XoboroArtworkResponse(
  val id: String,
  val type: String,
  val selected: Boolean,
  val mediaType: String,
  val fileSize: Long,
  val width: Int,
  val height: Int,
)

internal fun Artwork.toNativeArtworkResponse(): XoboroArtworkResponse =
  XoboroArtworkResponse(
    id = id.value,
    type = type.name,
    selected = selected,
    mediaType = mediaType,
    fileSize = fileSize,
    width = width,
    height = height,
  )
