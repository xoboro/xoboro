package io.xoboro.core.application

import io.xoboro.core.domain.BookId

/**
 * One media item inside an archive download, already cleared for the caller.
 *
 * A member carries an identifier rather than a [CatalogBook] because that is all the archive writer
 * needs: entry names come from the opened content stream, and the identifier is the fallback when a
 * stream does not know its own file name. Holding the catalog rows instead would keep a whole
 * series' metadata alive for the length of a multi-gigabyte download.
 *
 * [entryPrefix] is the member's one-based position in an *ordered* source, which is how a read-list
 * archive keeps its reading order visible after extraction - file names alone would sort
 * alphabetically. It is the position in the source list, not in the filtered result, so removing a
 * member the caller may not see leaves the remaining numbers where they were rather than silently
 * renumbering them.
 */
data class MediaArchiveMember(
  val bookId: BookId,
  val entryPrefix: Int? = null,
) {
  init {
    require(entryPrefix == null || entryPrefix > 0) {
      "Archive entry prefix must be positive"
    }
  }
}
