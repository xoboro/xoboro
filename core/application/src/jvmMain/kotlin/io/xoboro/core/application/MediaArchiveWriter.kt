package io.xoboro.core.application

import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a [MediaArchivePlan]'s members into a zip container.
 *
 * One member is open at a time and its bytes are copied through a fixed buffer, so peak memory does
 * not grow with the archive: a series of gigabytes streams in the same footprint as a single item.
 * Nothing is buffered to compute a length, which is why an archive response cannot advertise
 * `Content-Length`.
 *
 * Entries are stored rather than deflated. Comic archives, EPUBs and PDFs are already compressed, so
 * deflating them again spends the request's CPU to make the payload marginally larger.
 *
 * A member whose content cannot be opened is skipped rather than failing the archive. The response
 * status is long since sent by the time the first byte is written, so there is no status left to
 * change; a partial archive that omits a file the storage no longer has is more useful than a
 * truncated one.
 *
 * This lives in the application layer, beside the plan it consumes, because both HTTP surfaces need
 * it and it is the only piece of the two archive routes that touches a platform stream. It is
 * JVM-only for `java.util.zip`; nothing else in the plan is.
 */
class MediaArchiveWriter(
  private val content: BookContentAccess,
) {
  fun write(
    members: List<MediaArchiveMember>,
    output: OutputStream,
  ) {
    ZipOutputStream(output).use { archive ->
      archive.setLevel(Deflater.NO_COMPRESSION)
      val usedNames = mutableSetOf<String>()
      members.forEach { member -> archive.writeMember(member, usedNames) }
    }
  }

  private fun ZipOutputStream.writeMember(
    member: MediaArchiveMember,
    usedNames: MutableSet<String>,
  ) {
    val opened = content.openBook(member.bookId) ?: return
    try {
      val entryName =
        mediaArchiveEntryName(
          fileName = opened.fileName,
          fallback = "${member.bookId.value}$UNNAMED_MEMBER_EXTENSION",
          entryPrefix = member.entryPrefix,
        ).uniqueArchiveName(usedNames)
      putNextEntry(ZipEntry(entryName))
      val buffer = ByteArray(ARCHIVE_BUFFER_SIZE)
      while (true) {
        val read = opened.read(buffer)
        if (read < 0) break
        if (read > 0) write(buffer, 0, read)
      }
      closeEntry()
    } finally {
      opened.close()
    }
  }

  private companion object {
    const val ARCHIVE_BUFFER_SIZE = 64 * 1_024
    const val UNNAMED_MEMBER_EXTENSION = ".bin"
  }
}
