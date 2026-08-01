package io.xoboro.core.application

/**
 * The name to offer an archive download under.
 *
 * A series title or read-list name is user-supplied metadata, so it can carry path separators, drive
 * separators and control characters. Those are neutralised before the name reaches a
 * `Content-Disposition` header, where an extractor would read them as structure.
 *
 * The title is sanitized before the extension is appended, not after. Appending first - which is what
 * the Komga-compatible route used to do - meant the result was never blank, so a title made entirely
 * of characters this strips produced an archive named `.zip`, a hidden file on every Unix desktop.
 */
internal fun mediaArchiveFileName(title: String): String =
  "${title.withArchiveSafeCharacters().ifBlank { FALLBACK_ARCHIVE_STEM }}.zip"

/**
 * The entry name for one archived member, from the file name its content stream reports.
 *
 * Only the leaf is kept: an archive that reproduced library directory structure would disclose where
 * the server keeps its files. `\` is stripped as well as `/` because a Windows-sourced path can
 * reach here through an import.
 */
internal fun mediaArchiveEntryName(
  fileName: String?,
  fallback: String,
  entryPrefix: Int?,
): String {
  val leaf =
    fileName
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?.withArchiveSafeCharacters()
      ?.takeIf(String::isNotBlank)
      ?: fallback
  return entryPrefix?.let { "$it - $leaf" } ?: leaf
}

/**
 * Disambiguates an entry name against the ones already written.
 *
 * Two media items in one series can have the same file name in different directories, and a zip with
 * duplicate entry names extracts to one file - the archive would silently lose a volume.
 */
internal fun String.uniqueArchiveName(used: MutableSet<String>): String {
  if (used.add(this)) return this
  val stem = substringBeforeLast('.', this)
  val extension = substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
  var index = 2
  while (!used.add("$stem ($index)$extension")) index += 1
  return "$stem ($index)$extension"
}

private fun String.withArchiveSafeCharacters(): String =
  replace(UNSAFE_NAME_CHARACTERS, "_").trim()

private val UNSAFE_NAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private const val FALLBACK_ARCHIVE_STEM = "archive"
