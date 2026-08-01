package io.xoboro.server.media

import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.BookMedia
import io.xoboro.core.domain.BookPage
import io.xoboro.core.domain.Dimension
import io.xoboro.core.domain.MediaFile
import io.xoboro.core.domain.MediaFileKind
import io.xoboro.core.domain.MediaNavigationEntry
import io.xoboro.core.domain.MediaPosition
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.MediaStatus
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.ceil
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class EpubMediaAnalyzer(
  private val hasher: Xxh3ContentHasher = Xxh3ContentHasher(),
  private val pageHashing: Int = ZipMediaAnalyzer.DEFAULT_PAGE_HASHING,
  private val divinaLetterCountThreshold: Int = DEFAULT_DIVINA_LETTER_COUNT_THRESHOLD,
  private val encryptionProbe: ZipEncryptionProbe = ZipEncryptionProbe(),
) {
  init {
    require(pageHashing >= 0) { "Page hashing count must not be negative" }
    require(divinaLetterCountThreshold >= 0) {
      "DiViNa letter-count threshold must not be negative"
    }
  }

  fun analyze(
    bookId: BookId,
    path: Path,
    analyzeDimensions: Boolean,
    hashPages: Boolean = false,
    createdAtMillis: Long,
    updatedAtMillis: Long = createdAtMillis,
  ): BookMedia =
    try {
      ZipFile(path.toFile()).use { archive ->
        requireEpubMimetype(archive)
        val container = archive.readXml(CONTAINER_PATH)
        val packagePath =
          container
            .selectFirst("*|rootfile[full-path]")
            ?.attr("full-path")
            ?.let(::normalizeArchivePath)
            ?: throw IOException("EPUB package path is missing")
        val packageDocument = archive.readXml(packagePath)
        val packageDirectory = packagePath.substringBeforeLast('/', "")
        val manifest =
          packageDocument
            .select("*|manifest > *|item[id][href]")
            .associate { element ->
              element.attr("id") to
                ManifestItem(
                  id = element.attr("id"),
                  path = resolveArchivePath(packageDirectory, element.attr("href")),
                  mediaType =
                    element.attr("media-type").ifBlank { "application/octet-stream" },
                  properties =
                    element.attr("properties").split(WHITESPACE).filter(String::isNotBlank).toSet(),
                )
            }
        val spine =
          packageDocument
            .select("*|spine > *|itemref[idref]")
            .mapNotNull { manifest[it.attr("idref")] }
        if (spine.isEmpty()) throw IOException("EPUB spine is empty")
        val encryptedResources = archive.readEncryptedResourcePaths()
        if (spine.any { it.path in encryptedResources }) {
          return@use encryptedMedia(bookId, createdAtMillis, updatedAtMillis)
        }

        // Declared, not rendered: the cover comes from the manifest (an EPUB3
        // `properties="cover-image"` item, or the EPUB2 `<meta name="cover">` fallback), never
        // from rasterizing spine markup - this server has no browser engine to render XHTML with.
        val coverItem = findCoverItem(packageDocument, manifest)
        val files =
          manifest.values.map { item ->
            MediaFile(
              fileName = item.path,
              mediaType = item.mediaType,
              fileSize = archive.getEntry(item.path)?.knownSize(),
              kind =
                when {
                  item in spine -> MediaFileKind.EPUB_PAGE
                  item == coverItem -> MediaFileKind.EPUB_COVER
                  else -> MediaFileKind.EPUB_ASSET
                },
            )
          }
        val isKepub =
          spine.any { item ->
            item.mediaType.isMarkup() &&
              archive.readDocumentOrNull(item.path)?.getElementsByClass("koboSpan")
                ?.isNotEmpty() == true
          }
        val divinaPages =
          findDivinaPages(
            archive = archive,
            manifest = manifest.values,
            spine = spine,
            analyzeDimensions = analyzeDimensions,
            hashPages = hashPages,
          )
        val fixedLayout =
          divinaPages.isNotEmpty() ||
            packageDocument
              .selectFirst("*|metadata > *|meta[property=rendition:layout]")
              ?.text() == "pre-paginated" ||
            packageDocument
              .selectFirst("*|metadata > *|meta[name=fixed-layout]")
              ?.attr("content") == "true"
        val positions = computePositions(archive, spine, fixedLayout, isKepub)
        val navigation =
          readNavigation(
            archive = archive,
            packageDocument = packageDocument,
            packageDirectory = packageDirectory,
            manifest = manifest,
          )
        val missing =
          files.filter { it.fileSize == null }.map(MediaFile::fileName)
        BookMedia(
          bookId = bookId,
          status = MediaStatus.READY,
          mediaType = EPUB_MEDIA_TYPE,
          profile = MediaProfile.EPUB,
          pages = divinaPages,
          pageCount =
            if (divinaPages.isNotEmpty()) {
              divinaPages.size
            } else {
              spine.sumOf { item ->
                archive.getEntry(item.path)?.compressedSize
                  ?.takeIf { it >= 0 }
                  ?.let { maxOf(1, ceil(it / POSITION_BYTES.toDouble()).toInt()) }
                  ?: 0
              }
            },
          files = files,
          epubDivinaCompatible = divinaPages.isNotEmpty(),
          epubIsKepub = isKepub,
          epubIsFixedLayout = fixedLayout,
          toc = navigation.toc,
          landmarks = navigation.landmarks,
          pageList = navigation.pageList,
          positions = positions,
          comment =
            missing
              .takeIf(List<String>::isNotEmpty)
              ?.joinToString(prefix = "$ERROR_MISSING_RESOURCE [", postfix = "]"),
          createdAtMillis = createdAtMillis,
          updatedAtMillis = updatedAtMillis,
        )
      }
    } catch (_: ZipException) {
      if (encryptionProbe.declaresEncryptedEntries(path)) {
        encryptedMedia(bookId, createdAtMillis, updatedAtMillis)
      } else {
        errorMedia(bookId, createdAtMillis, updatedAtMillis)
      }
    } catch (_: Exception) {
      errorMedia(bookId, createdAtMillis, updatedAtMillis)
    }

  /**
   * Paths of resources that `META-INF/encryption.xml` declares as genuinely encrypted.
   *
   * That file is not a DRM marker on its own: the same mechanism carries EPUB font obfuscation,
   * which leaves the text perfectly readable. Only algorithms other than the two standard
   * obfuscation ones count, and the caller looks at spine resources alone, so an obfuscated font
   * can never make a readable book unsupported.
   */
  private fun ZipFile.readEncryptedResourcePaths(): Set<String> {
    val declaration = readXmlOrNull(ENCRYPTION_PATH) ?: return emptySet()
    return declaration
      .select("*|EncryptedData")
      .mapNotNullTo(mutableSetOf()) { data ->
        val algorithm =
          data.selectFirst("*|EncryptionMethod[Algorithm]")?.attr("Algorithm")
            ?: return@mapNotNullTo null
        if (algorithm in OBFUSCATION_ALGORITHMS) return@mapNotNullTo null
        data
          .selectFirst("*|CipherReference[URI]")
          ?.attr("URI")
          ?.let { runCatching { resolveArchivePath("", it) }.getOrNull() }
      }
  }

  /**
   * The manifest item the OPF declares as the cover image, if any.
   *
   * EPUB3 flags it with `properties="cover-image"` on the manifest item. EPUB2 has no such
   * property, so a book packaged for the older spec instead points to it indirectly with
   * `<meta name="cover" content="{manifest-item-id}"/>` in the metadata block. Either way the
   * result must actually be an image: a `cover-image` property or `cover` meta pointed at
   * markup would hand the generator a document to decode as a bitmap, which it cannot do.
   */
  private fun findCoverItem(
    packageDocument: Document,
    manifest: Map<String, ManifestItem>,
  ): ManifestItem? {
    val declared =
      manifest.values.firstOrNull { "cover-image" in it.properties }
        ?: packageDocument
          .selectFirst("*|metadata > *|meta[name=cover]")
          ?.attr("content")
          ?.let(manifest::get)
    return declared?.takeIf { it.mediaType.startsWith("image/", ignoreCase = true) }
  }

  private fun encryptedMedia(
    bookId: BookId,
    createdAtMillis: Long,
    updatedAtMillis: Long,
  ) = BookMedia(
    bookId = bookId,
    status = MediaStatus.UNSUPPORTED,
    mediaType = EPUB_MEDIA_TYPE,
    profile = MediaProfile.EPUB,
    comment = MediaAnalysisComment.ENCRYPTED,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

  private fun findDivinaPages(
    archive: ZipFile,
    manifest: Collection<ManifestItem>,
    spine: List<ManifestItem>,
    analyzeDimensions: Boolean,
    hashPages: Boolean,
  ): List<BookPage> {
    val paths =
      spine.map { item ->
        when {
          item.mediaType.startsWith("image/", ignoreCase = true) -> listOf(item.path)
          item.mediaType.isMarkup() -> {
            val document = archive.readDocumentOrNull(item.path) ?: return emptyList()
            if (document.body().text().length > divinaLetterCountThreshold) {
              return emptyList()
            }
            val directory = item.path.substringBeforeLast('/', "")
            (
              document.select("img[src]").map { it.attr("src") } +
                document.select("svg image").mapNotNull { image ->
                  image.attr("href").ifBlank { image.attr("xlink:href") }.ifBlank { null }
                }
            ).map { resolveArchivePath(directory, it) }.distinct()
          }
          else -> return emptyList()
        }
      }
    if (paths.any { it.size != 1 }) return emptyList()
    val imagePaths = paths.flatten()
    if (imagePaths.distinct().size != spine.size) return emptyList()
    val manifestByPath = manifest.associateBy(ManifestItem::path)
    val indexed =
      imagePaths.mapIndexed { index, imagePath ->
        val item = manifestByPath[imagePath] ?: return emptyList()
        if (!item.mediaType.startsWith("image/", ignoreCase = true)) return emptyList()
        val entry = archive.getEntry(imagePath) ?: return emptyList()
        BookPage(
          number = index + 1,
          fileName = imagePath,
          mediaType = item.mediaType,
          fileSize = entry.knownSize(),
          dimension =
            if (analyzeDimensions) {
              archive.getInputStream(entry).buffered().use(::readDimension)
            } else {
              null
            },
        )
      }
    if (!hashPages || pageHashing == 0) return indexed
    return indexed.mapIndexed { index, page ->
      if (index < pageHashing || index >= indexed.size - pageHashing) {
        page.copy(
          fileHash =
            runCatching {
              archive.getInputStream(requireNotNull(archive.getEntry(page.fileName)))
                .buffered()
                .use { hasher.hashPage(it, page.mediaType) }
            }.getOrDefault(""),
        )
      } else {
        page
      }
    }
  }

  private fun computePositions(
    archive: ZipFile,
    spine: List<ManifestItem>,
    fixedLayout: Boolean,
    isKepub: Boolean,
  ): List<MediaPosition> {
    var nextPosition = 1
    val raw =
      spine.flatMap { item ->
        val count =
          if (fixedLayout) {
            1
          } else {
            maxOf(
              1,
              ceil((archive.getEntry(item.path)?.knownSize() ?: 0L) / POSITION_BYTES.toDouble())
                .toInt(),
            )
          }
        val koboSpans =
          if (isKepub) {
            archive.readDocumentOrNull(item.path)
              ?.select("span.koboSpan[id]")
              ?.map(Element::id)
              .orEmpty()
          } else {
            emptyList()
          }
        (0 until count).map { index ->
          val progression = index.toFloat() / count
          PositionDraft(
            href = item.path,
            mediaType = item.mediaType,
            progression = progression,
            position = nextPosition++,
            koboSpan =
              when {
                fixedLayout || index == 0 -> "kobo.1.1"
                koboSpans.isEmpty() -> null
                else -> koboSpans[(index * koboSpans.size / count).coerceAtMost(koboSpans.lastIndex)]
              },
          )
        }
      }
    // `(position - 1) / count`: a Readium locator's `totalProgression` is where a position
    // *starts*, so the first position of any publication is 0 and the last of n is
    // `(n - 1) / n`. No position reports 1 — 1 is the end of the publication, which is not
    // a place a reader can be. `progression` stays the fraction through the resource and is
    // deliberately not folded in here: it is measured against the spine item, not against
    // the publication, so adding it would push a position past its own slot.
    return raw.map { draft ->
      MediaPosition(
        href = draft.href,
        mediaType = draft.mediaType,
        progression = draft.progression,
        position = draft.position,
        totalProgression = (draft.position - 1).toFloat() / raw.size,
        koboSpan = draft.koboSpan,
      )
    }
  }

  private fun readNavigation(
    archive: ZipFile,
    packageDocument: Document,
    packageDirectory: String,
    manifest: Map<String, ManifestItem>,
  ): Navigation {
    val navItem = manifest.values.firstOrNull { "nav" in it.properties }
    val navDocument = navItem?.let { archive.readDocumentOrNull(it.path) }
    val navDirectory = navItem?.path?.substringBeforeLast('/', "").orEmpty()
    fun epub3(type: String): List<MediaNavigationEntry> =
      navDocument
        ?.select("nav")
        ?.firstOrNull { nav ->
          nav.attr("epub:type").split(WHITESPACE).contains(type) ||
            nav.attr("type").split(WHITESPACE).contains(type)
        }
        ?.children()
        ?.firstOrNull { it.normalName() == "ol" }
        ?.let { parseHtmlNavigation(it, navDirectory) }
        .orEmpty()

    val ncxId = packageDocument.selectFirst("*|spine[toc]")?.attr("toc")
    val ncxItem =
      ncxId?.let(manifest::get)
        ?: manifest.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
    val ncxDocument = ncxItem?.let { archive.readXmlOrNull(it.path) }
    val ncxDirectory = ncxItem?.path?.substringBeforeLast('/', "").orEmpty()
    val toc =
      epub3("toc").ifEmpty {
        ncxDocument
          ?.selectFirst("*|navMap")
          ?.let { parseNcxNavigation(it, "navPoint", ncxDirectory) }
          .orEmpty()
      }
    val pageList =
      epub3("page-list").ifEmpty {
        ncxDocument
          ?.selectFirst("*|pageList")
          ?.let { parseNcxNavigation(it, "pageTarget", ncxDirectory) }
          .orEmpty()
      }
    val landmarks =
      epub3("landmarks").ifEmpty {
        packageDocument.select("*|guide > *|reference").mapNotNull { reference ->
          val title =
            reference.attr("title").ifBlank { reference.attr("type") }.ifBlank { null }
              ?: return@mapNotNull null
          MediaNavigationEntry(
            title = title,
            href = resolveArchiveHref(packageDirectory, reference.attr("href")),
          )
        }
      }
    return Navigation(toc, landmarks, pageList)
  }

  private fun parseHtmlNavigation(
    list: Element,
    directory: String,
  ): List<MediaNavigationEntry> =
    list.children().filter { it.normalName() == "li" }.mapNotNull { item ->
      val label =
        item.children().firstOrNull { it.normalName() in setOf("a", "span") }
          ?: return@mapNotNull null
      val title = label.text().ifBlank { return@mapNotNull null }
      val nested = item.children().firstOrNull { it.normalName() == "ol" }
      MediaNavigationEntry(
        title = title,
        href = label.attr("href").ifBlank { null }?.let { resolveArchiveHref(directory, it) },
        children = nested?.let { parseHtmlNavigation(it, directory) }.orEmpty(),
      )
    }

  private fun parseNcxNavigation(
    parent: Element,
    childTag: String,
    directory: String,
  ): List<MediaNavigationEntry> =
    parent.children().filter { it.normalName() == childTag.lowercase() }.mapNotNull { item ->
      val title =
        item.selectFirst("*|navLabel > *|text")?.text()?.ifBlank { null }
          ?: return@mapNotNull null
      MediaNavigationEntry(
        title = title,
        href =
          item.selectFirst("*|content[src]")?.attr("src")?.ifBlank { null }
            ?.let { resolveArchiveHref(directory, it) },
        children = parseNcxNavigation(item, childTag, directory),
      )
    }

  private fun requireEpubMimetype(archive: ZipFile) {
    val mimetype =
      archive.getEntry(MIMETYPE_PATH)
        ?.let { archive.readEntryBytes(it, MAX_MIMETYPE_BYTES).decodeToString() }
        ?.trim()
    if (mimetype != EPUB_MEDIA_TYPE) throw IOException("Invalid EPUB mimetype")
  }

  private fun ZipFile.readXml(path: String): Document =
    getEntry(path)?.let { entry ->
      ByteArrayInputStream(readEntryBytes(entry, MAX_MARKUP_BYTES)).use {
        Jsoup.parse(it, null, "", Parser.xmlParser())
      }
    } ?: throw IOException("EPUB entry is missing: $path")

  private fun ZipFile.readXmlOrNull(path: String): Document? =
    runCatching { readXml(path) }.getOrNull()

  private fun ZipFile.readDocumentOrNull(path: String): Document? =
    getEntry(path)?.let { entry ->
      runCatching {
        ByteArrayInputStream(readEntryBytes(entry, MAX_MARKUP_BYTES)).use {
          Jsoup.parse(it, null, "")
        }
      }.getOrNull()
    }

  private fun ZipFile.readEntryBytes(
    entry: ZipEntry,
    maximumBytes: Int,
  ): ByteArray {
    require(entry.knownSize()?.let { it <= maximumBytes } != false) {
      "EPUB markup exceeds the safety limit"
    }
    return getInputStream(entry).use { input ->
      val bytes = input.readNBytes(maximumBytes + 1)
      require(bytes.size <= maximumBytes) { "EPUB markup exceeds the safety limit" }
      bytes
    }
  }

  private fun readDimension(input: java.io.InputStream): Dimension? =
    ImageIO.createImageInputStream(input)?.use { imageInput ->
      val readers = ImageIO.getImageReaders(imageInput)
      if (!readers.hasNext()) return@use null
      val reader = readers.next()
      try {
        reader.setInput(imageInput, true, true)
        Dimension(reader.getWidth(0), reader.getHeight(0))
      } finally {
        reader.dispose()
      }
    }

  private fun errorMedia(
    bookId: BookId,
    createdAtMillis: Long,
    updatedAtMillis: Long,
  ) = BookMedia(
    bookId = bookId,
    status = MediaStatus.ERROR,
    mediaType = EPUB_MEDIA_TYPE,
    profile = MediaProfile.EPUB,
    comment = MediaAnalysisComment.UNREADABLE_CONTAINER,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
  )

  private data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
  )

  private data class PositionDraft(
    val href: String,
    val mediaType: String,
    val progression: Float,
    val position: Int,
    val koboSpan: String?,
  )

  private data class Navigation(
    val toc: List<MediaNavigationEntry>,
    val landmarks: List<MediaNavigationEntry>,
    val pageList: List<MediaNavigationEntry>,
  )

  companion object {
    const val EPUB_MEDIA_TYPE: String = "application/epub+zip"
    const val ERROR_MISSING_RESOURCE: String = "ERR_1033"
    const val DEFAULT_DIVINA_LETTER_COUNT_THRESHOLD: Int = 15
    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val ENCRYPTION_PATH = "META-INF/encryption.xml"

    /**
     * The two algorithms that mangle embedded fonts rather than protect content: the EPUB 3
     * resource-obfuscation method and Adobe's older equivalent. Neither stops a reader.
     */
    private val OBFUSCATION_ALGORITHMS =
      setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
      )
    private const val MIMETYPE_PATH = "mimetype"
    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
    private const val POSITION_BYTES = 1_024L
    private const val MAX_MIMETYPE_BYTES = 256
    private const val MAX_MARKUP_BYTES = 16 * 1_024 * 1_024
    private val WHITESPACE = Regex("\\s+")
  }
}

private fun String.isMarkup(): Boolean =
  equals("application/xhtml+xml", ignoreCase = true) ||
    equals("text/html", ignoreCase = true) ||
    equals("image/svg+xml", ignoreCase = true)

private fun ZipEntry.knownSize(): Long? = size.takeIf { it >= 0 }

private fun resolveArchivePath(
  directory: String,
  href: String,
): String {
  val path = href.substringBefore('#').substringBefore('?')
  val decoded = URLDecoder.decode(path, Charsets.UTF_8)
  return normalizeArchivePath(
    if (directory.isBlank()) decoded else "$directory/$decoded",
  )
}

private fun resolveArchiveHref(
  directory: String,
  href: String,
): String {
  val fragment = href.substringAfter('#', "").takeIf(String::isNotEmpty)
  val path = resolveArchivePath(directory, href)
  return fragment?.let { "$path#$it" } ?: path
}

private fun normalizeArchivePath(path: String): String {
  val normalized = Paths.get(path.replace('\\', '/')).normalize()
  require(!normalized.isAbsolute && !normalized.startsWith("..")) {
    "EPUB resource escapes the archive root"
  }
  return normalized.joinToString("/")
}
