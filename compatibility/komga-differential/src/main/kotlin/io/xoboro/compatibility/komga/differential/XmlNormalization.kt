package io.xoboro.compatibility.komga.differential

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal class XmlNormalizer(
  ignorePaths: Set<String>,
) {
  private val ignored = ignorePaths.map(::XmlPathPattern)

  fun normalize(bytes: ByteArray): String {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      }
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    return requireNotNull(normalizeElement(document.documentElement, emptyList())) {
      "XML document root must not be ignored"
    }
  }

  private fun normalizeElement(
    element: Element,
    parentPath: List<String>,
  ): String? {
    val path = parentPath + element.localNameOrNodeName()
    if (ignored.any { it.matches(path) }) return null

    val attributes =
      (0 until element.attributes.length)
        .map { element.attributes.item(it) }
        .filterNot { it.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI }
        .filterNot { attribute ->
          ignored.any { it.matches(path + "@${attribute.localNameOrNodeName()}") }
        }.sortedWith(compareBy<Node>({ it.namespaceURI.orEmpty() }, { it.localNameOrNodeName() }))
        .joinToString(separator = "") { attribute ->
          "[${attribute.expandedName()}=${attribute.nodeValue.escaped()}]"
        }

    val children =
      (0 until element.childNodes.length)
        .map { element.childNodes.item(it) }
        .mapNotNull { child ->
          when (child.nodeType) {
            Node.ELEMENT_NODE -> normalizeElement(child as Element, path)
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
              child.nodeValue
                ?.takeUnless(String::isBlank)
                ?.escaped()
                ?.let { "#text=$it" }
            else -> null
          }
        }.joinToString(separator = "")

    return "<${element.expandedName()}$attributes>$children</${element.expandedName()}>"
  }

  private fun Node.localNameOrNodeName(): String = localName ?: nodeName.substringAfter(':')

  private fun Node.expandedName(): String =
    namespaceURI?.let { "{$it}${localNameOrNodeName()}" } ?: localNameOrNodeName()

  private fun String.escaped(): String =
    replace("\\", "\\\\")
      .replace("[", "\\[")
      .replace("]", "\\]")
      .replace("<", "\\<")
      .replace(">", "\\>")
}

internal class XmlPathPattern private constructor(
  private val segments: List<String>,
) {
  constructor(value: String) : this(parse(value))

  fun matches(path: List<String>): Boolean =
    segments.size == path.size &&
      segments.zip(path).all { (expected, actual) -> expected == "*" || expected == actual }

  companion object {
    fun validate(value: String) {
      parse(value)
    }

    private fun parse(value: String): List<String> {
      require(value.startsWith("/")) {
        "XML path must start with '/'"
      }
      require(value.length > 1) { "Root XML path is not supported" }
      return value
        .removePrefix("/")
        .split("/")
        .onEach { segment ->
          require(segment.isNotEmpty()) { "XML path segments must not be empty" }
          require(segment == "*" || XML_NAME.matches(segment) || XML_ATTRIBUTE.matches(segment)) {
            "XML path contains an invalid segment"
          }
        }
    }

    private val XML_NAME = Regex("[A-Za-z_][A-Za-z0-9._-]*")
    private val XML_ATTRIBUTE = Regex("@[A-Za-z_][A-Za-z0-9._-]*")
  }
}
