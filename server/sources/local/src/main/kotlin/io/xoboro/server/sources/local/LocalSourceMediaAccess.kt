package io.xoboro.server.sources.local

import io.xoboro.server.media.MaterializedMedia
import io.xoboro.server.media.SourceMediaAccess
import java.nio.file.Path

class LocalSourceMediaAccess : SourceMediaAccess {
  override val sourceId: String = "local"

  override fun materialize(
    rootItemId: String,
    itemId: String,
  ): MaterializedMedia = LocalMaterializedMedia(LocalMediaItemPath.resolve(rootItemId, itemId))

  private class LocalMaterializedMedia(
    override val path: Path,
  ) : MaterializedMedia {
    override fun close() = Unit
  }
}
