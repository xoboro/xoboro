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
 * publication at `0`, so this is one position ahead of that convention. `docs/api/native-v1.md`
 * records why it is documented rather than corrected — the same value already feeds Kobo's
 * `ProgressPercent`, so the arithmetic is not this endpoint's to change.
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
