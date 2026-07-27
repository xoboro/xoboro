package io.xoboro.server.metadata

import io.xoboro.core.application.ReadListImportBookRequest
import io.xoboro.core.application.ReadListImportException
import io.xoboro.core.application.ReadListImportParser
import io.xoboro.core.application.ReadListImportRequest
import io.xoboro.core.application.ReadListImportLifecycle
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ComicRackReadListParser : ReadListImportParser {
  override fun parse(bytes: ByteArray): ReadListImportRequest {
    if (bytes.isEmpty() || bytes.size > ReadListImportLifecycle.MAXIMUM_UPLOAD_BYTES) {
      fail("ERR_1015")
    }
    val document =
      try {
        secureDocumentBuilderFactory()
          .newDocumentBuilder()
          .parse(ByteArrayInputStream(bytes))
      } catch (_: Exception) {
        fail("ERR_1015")
      }
    val root = document.documentElement ?: fail("ERR_1015")
    val name =
      root.firstElementText("Name")
        ?.takeIf(String::isNotBlank)
        ?: fail("ERR_1030")
    val bookNodes = root.getElementsByTagName("Book")
    if (bookNodes.length == 0) fail("ERR_1029")
    val books =
      (0 until bookNodes.length).map { index ->
        val element = bookNodes.item(index) as? Element ?: fail("ERR_1015")
        val series = element.value("Series")?.takeIf(String::isNotBlank) ?: fail("ERR_1031")
        val number = element.value("Number")?.trim() ?: fail("ERR_1031")
        val volume = element.value("Volume")?.trim()?.toIntOrNull()
        val withVolume =
          if (volume == null || volume == 1) {
            series
          } else {
            "$series ($volume)"
          }
        ReadListImportBookRequest(
          series = linkedSetOf(withVolume, series),
          number = number,
        )
      }
    return ReadListImportRequest(name, books)
  }

  private fun Element.value(name: String): String? =
    getAttribute(name).takeIf(String::isNotEmpty) ?: firstElementText(name)

  private fun Element.firstElementText(name: String): String? =
    getElementsByTagName(name)
      .item(0)
      ?.textContent
      ?.trim()

  private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      isXIncludeAware = false
      isExpandEntityReferences = false
    }

  private fun fail(code: String): Nothing =
    throw ReadListImportException(code)
}
