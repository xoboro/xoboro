package io.xoboro.server.sources.webdav

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class WebDavMultiStatusParseException(
  reason: String,
  cause: Throwable? = null,
) : IllegalStateException("Could not parse WebDAV multi-status response: $reason", cause)

/**
 * Parses a `207 Multi-Status` PROPFIND body into [WebDavResource] entries.
 *
 * Uses `javax.xml`/`org.w3c.dom`, the same JDK DOM API `ComicRackReadListParser` already uses for
 * untrusted XML elsewhere in this repository, with the same hardening against external entities
 * and DTDs - a PROPFIND response is server-controlled input just like an imported read list.
 *
 * Elements are matched by namespace URI (`DAV:`) rather than by tag name, because the `D:`/`d:`
 * prefix in front of `href`, `propstat`, `resourcetype`, and so on is only a convention: the spec
 * fixes the namespace, not the prefix a given server chooses to bind it to.
 */
object WebDavMultiStatusParser {
  private const val DAV_NAMESPACE = "DAV:"

  fun parse(
    body: ByteArray,
    requestUri: URI,
  ): List<WebDavResource> {
    val document =
      try {
        secureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(body))
      } catch (failure: Exception) {
        throw WebDavMultiStatusParseException(failure.message ?: failure::class.simpleName.orEmpty(), failure)
      }
    val root = document.documentElement ?: throw WebDavMultiStatusParseException("empty document")
    val responses = root.childElementsNS("response")
    if (responses.isEmpty()) throw WebDavMultiStatusParseException("no <response> entries")
    return responses.map { response -> response.toResource(requestUri) }
  }

  private fun Element.toResource(requestUri: URI): WebDavResource {
    val href =
      firstChildElementNS("href")?.textContent?.trim()?.takeIf(String::isNotBlank)
        ?: throw WebDavMultiStatusParseException("<response> missing <href>")
    val url = requestUri.resolve(URI(href)).toString()
    val propStat = successfulPropStat() ?: return WebDavResource(url, isCollection = false, null, null, null)
    val prop = propStat.firstChildElementNS("prop")
    val isCollection = prop?.firstChildElementNS("resourcetype")?.firstChildElementNS("collection") != null
    val contentLength = prop?.firstChildElementNS("getcontentlength")?.textContent?.trim()?.toLongOrNull()
    val lastModified = prop?.firstChildElementNS("getlastmodified")?.textContent?.trim()?.takeIf(String::isNotBlank)
    val etag = prop?.firstChildElementNS("getetag")?.textContent?.trim()?.trim('"')?.takeIf(String::isNotBlank)
    return WebDavResource(url, isCollection, contentLength, lastModified, etag)
  }

  /** A `<response>` may carry several `<propstat>` blocks (e.g. one 200, one 404-for-a-missing-property); this picks the successful one. */
  private fun Element.successfulPropStat(): Element? =
    childElementsNS("propstat").firstOrNull { propStat ->
      propStat.firstChildElementNS("status")?.textContent?.contains(" 200 ") == true
    }

  private fun Element.childElementsNS(localName: String): List<Element> {
    val children = mutableListOf<Element>()
    var node: Node? = firstChild
    while (node != null) {
      if (node is Element && node.localName == localName && node.namespaceURI == DAV_NAMESPACE) children += node
      node = node.nextSibling
    }
    return children
  }

  private fun Element.firstChildElementNS(localName: String): Element? = childElementsNS(localName).firstOrNull()

  private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      isXIncludeAware = false
      isExpandEntityReferences = false
    }
}
