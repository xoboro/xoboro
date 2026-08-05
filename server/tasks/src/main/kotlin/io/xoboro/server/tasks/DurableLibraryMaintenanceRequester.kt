package io.xoboro.server.tasks

import io.xoboro.core.application.LibraryMaintenanceRequester
import io.xoboro.core.application.TaskEnqueue
import io.xoboro.core.domain.LibraryId

class DurableLibraryMaintenanceRequester(
  private val analysis: AnalyzeBookTaskEmitter,
  private val metadata: RefreshMetadataTaskEmitter,
  private val trash: EmptyLibraryTrashTaskEmitter,
) : LibraryMaintenanceRequester {
  override fun analyze(libraryId: LibraryId): TaskEnqueue =
    analysis.analyzeLibraryDeferred(libraryId)

  override fun refreshMetadata(libraryId: LibraryId): TaskEnqueue =
    metadata.refreshLibraryDeferred(libraryId)

  override fun emptyTrash(libraryId: LibraryId): Boolean =
    trash.emptyTrash(libraryId)
}
