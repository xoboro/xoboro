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
 * [totalProgression] is `position / count`, so it is the progress at the **end** of this
 * position: the first of two positions reports `0.5`, not `0`. A Readium locator starts a
 * publication at `0`, so this is one position ahead of that convention.
 *
 * Documented rather than corrected because `KoreaderSyncRoutes` inverts it —
 * `round(pageCount * totalProgression)` becomes persisted read progress — so correcting this
 * formula means correcting that mapping in the same change. Under Readium's
 * `(position - 1) / count` the last position maps to `round(pageCount * (n - 1) / n)`, which is
 * no longer `pageCount` by construction, and stored KOReader pages shift by up to about one.
 * Whether the final page actually becomes unreachable depends on how `pageCount` compares to
 * `positions.size`: page count is derived from compressed archive size while positions are
 * chunked on uncompressed size, so the two differ and the expression frequently rounds back up
 * to `pageCount`. Kobo's `ProgressPercent` is affected too, as a displayed number.
 * `docs/architecture/0105-total-progression-convention.md` has the full account.
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
