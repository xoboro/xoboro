package io.xoboro.server.media

/**
 * Guesses an archive entry's media type from its file name.
 *
 * Every other analyzer sniffs content with Tika, which is strictly better and needs the entry's
 * bytes. On the [SourceRandomAccess] path those bytes are exactly what is not being fetched, so the
 * name is all there is - and a name is a claim, not evidence. Two consequences the caller inherits:
 *
 * - A `.jpg` that is really a text file is listed as a page here and would have been excluded by
 *   content sniffing. It fails later, when the page is actually served.
 * - An image with no extension, or an extension not in [MEDIA_TYPES], is listed as a non-image file
 *   rather than as a page, so the book reports fewer pages than it has.
 *
 * Both are why this is reached only when a library has opted out of dimension analysis: a library
 * that wants its pages verified is already paying to read them.
 */
object MediaTypeByFileName {
  fun detect(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    if (extension.isEmpty() || extension.length == fileName.length) return null
    return MEDIA_TYPES[extension.lowercase()]
  }

  /**
   * Image types the reader can page through, plus the sidecar types a comic archive carries.
   *
   * Deliberately not exhaustive: an unrecognized extension becomes `null`, which lists the entry as
   * a file instead of guessing at a page the reader may not be able to decode.
   */
  private val MEDIA_TYPES: Map<String, String> =
    mapOf(
      "jpg" to "image/jpeg",
      "jpeg" to "image/jpeg",
      "jpe" to "image/jpeg",
      "png" to "image/png",
      "gif" to "image/gif",
      "webp" to "image/webp",
      "avif" to "image/avif",
      "jxl" to "image/jxl",
      "bmp" to "image/bmp",
      "tif" to "image/tiff",
      "tiff" to "image/tiff",
      "heic" to "image/heic",
      "heif" to "image/heif",
      "xml" to "application/xml",
      "txt" to "text/plain",
      "json" to "application/json",
      "nfo" to "text/plain",
      "pdf" to "application/pdf",
    )
}
