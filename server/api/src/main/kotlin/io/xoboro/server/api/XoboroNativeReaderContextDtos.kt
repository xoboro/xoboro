package io.xoboro.server.api

import io.xoboro.core.application.CatalogBookReaderContext
import io.xoboro.core.domain.MediaKind
import kotlinx.serialization.Serializable

@Serializable
data class XoboroMediaItemReaderContextResponse(
  val item: XoboroMediaItemResponse,
  /** Omitted by production JSON serialization when the current item is first. */
  val previousId: String?,
  /** Omitted by production JSON serialization when the current item is last. */
  val nextId: String?,
  val pages: List<XoboroMediaPageResponse>,
  val positions: List<XoboroMediaPositionResponse>,
)

internal fun CatalogBookReaderContext.toNativeResponse(): XoboroMediaItemReaderContextResponse =
  XoboroMediaItemReaderContextResponse(
    item = item.toNativeResponse(),
    previousId = previousId?.value,
    nextId = nextId?.value,
    pages =
      if (item.book.mediaKind == MediaKind.EPUB) {
        emptyList()
      } else {
        item.media?.pages.orEmpty().map { it.toNativeResponse() }
      },
    positions =
      if (item.book.mediaKind == MediaKind.EPUB) {
        item.media?.positions.orEmpty().map { it.toNativeResponse() }
      } else {
        emptyList()
      },
  )
