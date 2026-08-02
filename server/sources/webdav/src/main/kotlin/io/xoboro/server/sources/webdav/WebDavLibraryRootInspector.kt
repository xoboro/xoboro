package io.xoboro.server.sources.webdav

import io.xoboro.core.application.LibraryRootInspector
import io.xoboro.core.application.RootType

/**
 * Answers root validation questions (`LibraryLifecycle`'s [io.xoboro.core.application.LibraryRootAccess])
 * for a WebDAV root, entirely through PROPFIND `Depth: 0` requests.
 *
 * Unlike [io.xoboro.server.sources.local.LocalLibraryRootInspector], there is no real-path
 * resolution step: a WebDAV href has no symlink-like alias, so [isSameOrAncestor] is a plain URL
 * comparison and never touches the network.
 */
class WebDavLibraryRootInspector(
  override val sourceId: String = SOURCE_ID,
  private val httpClient: WebDavHttpClient = WebDavHttpClient(),
  private val environment: Map<String, String> = System.getenv(),
) : LibraryRootInspector {
  init {
    require(sourceId.isNotBlank()) { "WebDAV source ID must not be blank" }
  }

  /**
   * Treats any request failure - `404`, `401`, a network error - as [RootType.MISSING], mirroring
   * `java.nio.file.Files.exists`'s never-throw contract that [io.xoboro.server.sources.local.LocalLibraryRootInspector.typeOf]
   * relies on. A malformed root URL still throws [InvalidWebDavItemException], since that is a
   * configuration mistake rather than a transient availability question.
   */
  override fun typeOf(itemId: String): RootType {
    val root = itemId.parseWebDavRoot()
    val self = describeSelf(root) ?: return RootType.MISSING
    return if (self.isCollection) RootType.DIRECTORY else RootType.FILE
  }

  override fun isReadable(itemId: String): Boolean = describeSelf(itemId.parseWebDavRoot()) != null

  override fun isSameOrAncestor(
    possibleAncestorItemId: String,
    possibleDescendantItemId: String,
  ): Boolean {
    val ancestor = possibleAncestorItemId.parseWebDavRoot().baseUrl
    val descendant = possibleDescendantItemId.parseWebDavRoot().baseUrl
    return runCatching { WebDavPaths.requireWithinRoot(ancestor, descendant, "root") }.isSuccess
  }

  private fun describeSelf(root: WebDavRootLocation): WebDavResource? =
    try {
      val credentials = WebDavCredentialsResolver.resolve(root.credentialId, environment)
      httpClient.propfind(root.baseUrl, credentials, depth = 0).firstOrNull()
    } catch (_: WebDavRequestFailedException) {
      null
    }

  companion object {
    const val SOURCE_ID: String = "webdav"
  }
}
