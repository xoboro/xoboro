package io.xoboro.server.media

/**
 * Stable codes stored in [io.xoboro.core.domain.BookMedia.comment] to explain an analysis outcome.
 *
 * Clients branch on these strings, so they are a contract and not a log message. They live here
 * rather than in one analyzer's companion because every analyzer reports the same vocabulary: ZIP,
 * RAR and PDF all need [NO_PAGES], [UNREADABLE_CONTAINER] and [ENCRYPTED], and duplicating the
 * literals per format is how two analyzers end up disagreeing about what `ERR_1008` means.
 *
 * `ERR_10xx` codes are the ones Komga defines, kept identical so an imported library keeps its
 * diagnoses (ADR 0009). Codes from `ERR_1100` up are Xoboro's own; the split exists so adopting a
 * future Komga code can never silently reinterpret one of ours.
 */
object MediaAnalysisComment {
  /** The container was readable but held no image the reader could page through. */
  const val NO_PAGES: String = "ERR_1006"

  /** Individual entries failed detection; the names follow the code in brackets. */
  const val UNREADABLE_ENTRY: String = "ERR_1007"

  /** The container itself could not be opened or walked - truncated, corrupt, or not the format. */
  const val UNREADABLE_CONTAINER: String = "ERR_1008"

  /**
   * The container is intact but its content is encrypted, so no credential Xoboro holds can read
   * it. Distinct from [UNREADABLE_CONTAINER] because the two demand opposite responses: a corrupt
   * file may analyze on retry once the storage settles, while an encrypted one never will until the
   * file itself is replaced. Media carrying this code is [io.xoboro.core.domain.MediaStatus
   * .UNSUPPORTED], not `ERROR`.
   */
  const val ENCRYPTED: String = "ERR_1101"

  /**
   * A multipart archive is missing a volume, so the set cannot be walked end to end. Kept apart from
   * [UNREADABLE_CONTAINER] because the file at hand is not damaged - the diagnosis points at a
   * sibling that is absent, and the media analyzes normally once that sibling is restored.
   */
  const val INCOMPLETE_VOLUME_SET: String = "ERR_1102"
}
