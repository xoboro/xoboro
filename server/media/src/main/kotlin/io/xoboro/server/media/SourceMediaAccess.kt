package io.xoboro.server.media

import java.nio.file.Path

interface MaterializedMedia : AutoCloseable {
  val path: Path
}

interface SourceMediaAccess {
  val sourceId: String

  fun materialize(
    rootItemId: String,
    itemId: String,
  ): MaterializedMedia
}

class UnknownSourceMediaAccessException(
  sourceId: String,
) : IllegalArgumentException("No media access registered for source: $sourceId")
