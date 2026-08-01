package io.xoboro.core.application

/**
 * A complete archive download: the name to offer it under, and the members to put in it, in order.
 *
 * This is the boundary both HTTP surfaces cross. Library grants and content restrictions have
 * already been applied to [members] by [MediaArchivePlanner], so a surface that streams a plan
 * cannot forget to apply them - which is the mistake the obvious implementation makes, because
 * "fetch the series' items and zip them" reads as complete.
 *
 * An empty [members] list is a real answer rather than an error: a source can exist, be visible, and
 * contain nothing this caller may read. What to do with it belongs to the surface, because the two
 * surfaces answer differently and are allowed to.
 */
data class MediaArchivePlan(
  val fileName: String,
  val members: List<MediaArchiveMember>,
)
