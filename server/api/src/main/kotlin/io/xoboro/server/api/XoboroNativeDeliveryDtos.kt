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
 * [totalProgression] how far into the whole publication, which together are what the
 * read-progress endpoint's Readium locator carries. [koboSpan] is present only for a
 * KEPUB and is what Kobo devices anchor to.
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
