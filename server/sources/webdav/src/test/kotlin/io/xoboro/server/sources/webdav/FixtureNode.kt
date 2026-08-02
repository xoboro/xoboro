package io.xoboro.server.sources.webdav

/** A synthetic WebDAV tree served by [FakeWebDavServer]. Never real titles or media - see `docs/testing.md`. */
sealed interface FixtureNode {
  val name: String
}

data class FixtureFile(
  override val name: String,
  val bytes: ByteArray,
  val etag: String? = null,
  val lastModifiedHttpDate: String? = null,
  val omitContentLength: Boolean = false,
) : FixtureNode

data class FixtureDirectory(
  override val name: String,
  val children: MutableList<FixtureNode> = mutableListOf(),
) : FixtureNode
