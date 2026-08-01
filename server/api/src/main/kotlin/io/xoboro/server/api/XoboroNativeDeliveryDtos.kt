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

/**
 * One EPUB reading position.
 *
 * This is what makes an EPUB reader possible. `/resources` lists the container's
 * indexed entries in stored order, which is the **OPF manifest** order — the order the
 * packager happened to write them in, not the order they are read in. Positions are
 * derived from the spine, so `position` ascending *is* reading order.
 *
 * [href] is the resource path to request verbatim, matching a `path` from the resource
 * manifest. [progression] is how far into that resource the position sits and
 * [totalProgression] how far into the whole publication. Both are copied into the
 * read-progress endpoint's locator. [koboSpan] is present only for a KEPUB and is what
 * Kobo devices anchor to.
 *
 * [totalProgression] is `(position - 1) / count`, where a Readium locator's `totalProgression`
 * sits: the **start** of this position. The first position of any publication reports `0`, and
 * the last of `n` reports `(n - 1) / n`. No position reports `1`, because `1` is the end of the
 * publication rather than a place a reader can be — use `position == count` to recognise the last
 * position, not `totalProgression == 1`.
 *
 * This was `position / count` until ADR 0106 corrected it, so a client that hard-coded the old
 * values sees each position move back by `1 / count`.
 * `docs/architecture/0106-readium-total-progression.md` has the account, including why the
 * KOReader page mapping no longer reads this field at all.
 */
@Serializable
data class XoboroMediaPositionResponse(
  val position: Int,
  val href: String,
  val mediaType: String,
  val progression: Float,
  val totalProgression: Float,
  val koboSpan: String? = null,
)
