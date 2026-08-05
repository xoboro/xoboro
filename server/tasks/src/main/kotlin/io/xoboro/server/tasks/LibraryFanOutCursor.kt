package io.xoboro.server.tasks

/**
 * How far a library fan-out got, so its successor resumes instead of starting over.
 *
 * A library fan-out is one enqueue per series and per book - 24,696 for a library of 314 webtoon
 * series. Any one of those enqueues meeting a busy store throws, which defers the whole task and
 * re-runs it from the beginning. That is not merely wasteful: a task row is deleted when it
 * completes, so a restarted pass re-queues every book it had already finished analysing, and if
 * contention reliably arrives partway through a library large enough, the pass never reaches its
 * end at all. Ordering series before books only chose which half survived that.
 *
 * A cursor turns the fan-out into a chain of bounded chunks, each its own task with its own lease.
 * Contention then costs at most the chunk in flight rather than the pass, and the chain continues
 * from where the cursor left it.
 *
 * [Stage] exists because a refresh fans out over two collections and the boundary between them has
 * to survive a restart too. An analysis fan-out has only books, so it starts at [Stage.BOOKS] and
 * never sees the other one.
 *
 * [afterId] is exclusive and ordered by id, which is why the fan-out sorts: resuming needs a total
 * order that does not depend on the order rows happen to come back in.
 */
data class LibraryFanOutCursor(
  val stage: Stage,
  val afterId: String? = null,
) {
  init {
    require(afterId == null || afterId.isNotBlank()) { "Fan-out cursor id must not be blank" }
  }

  enum class Stage {
    SERIES,
    BOOKS,
  }

  /**
   * Encoded into a task id as well as a payload, so it must contain no character that would make
   * two different cursors collide. Ids are opaque strings, so the stage is separated by a delimiter
   * that an id cannot contribute.
   */
  fun encode(): String = "${stage.name}$DELIMITER${afterId.orEmpty()}"

  companion object {
    /** The start of a refresh fan-out, which has series to do before it reaches books. */
    val START: LibraryFanOutCursor = LibraryFanOutCursor(Stage.SERIES)

    /** The start of a fan-out with no series stage. */
    val BOOKS_START: LibraryFanOutCursor = LibraryFanOutCursor(Stage.BOOKS)

    private const val DELIMITER = '|'

    /**
     * Answers [fallback] for anything unparseable rather than throwing.
     *
     * A cursor is an optimisation, not a fact the fan-out needs: restarting a pass is correct, only
     * slow. Failing the task instead would turn a payload written by an older build into a library
     * that never refreshes.
     */
    fun decode(
      raw: String?,
      fallback: LibraryFanOutCursor,
    ): LibraryFanOutCursor {
      val value = raw?.takeIf(String::isNotBlank) ?: return fallback
      val stageName = value.substringBefore(DELIMITER)
      val stage = Stage.entries.firstOrNull { it.name == stageName } ?: return fallback
      val afterId = value.substringAfter(DELIMITER, missingDelimiterValue = "").takeIf(String::isNotBlank)
      return LibraryFanOutCursor(stage, afterId)
    }
  }
}
