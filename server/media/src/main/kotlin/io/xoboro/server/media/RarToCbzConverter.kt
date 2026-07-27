package io.xoboro.server.media

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RarToCbzConverter(
  private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
  private val maximumExpandedBytes: Long = DEFAULT_MAXIMUM_EXPANDED_BYTES,
  private val maximumDictionarySize: Long = RarMediaAnalyzer.DEFAULT_MAXIMUM_DICTIONARY_SIZE,
) {
  init {
    require(maximumEntries > 0) { "Archive entry limit must be positive" }
    require(maximumExpandedBytes > 0) { "Expanded archive limit must be positive" }
    require(maximumDictionarySize > 0) { "RAR dictionary limit must be positive" }
  }

  fun convert(
    source: Path,
    destination: Path,
  ): ArchiveConversionResult {
    require(source.toAbsolutePath().normalize() != destination.toAbsolutePath().normalize()) {
      "RAR conversion destination must differ from the source"
    }
    require(ArchiveFormatDetector().detect(source)?.isRar == true) {
      "Only RAR archives can be converted to CBZ"
    }
    Files.createDirectories(requireNotNull(destination.parent))
    var entryCount = 0
    var expandedBytes = 0L
    val names = mutableSetOf<String>()
    try {
      Archive(
        source.toFile(),
        ArchiveOptions.builder().maxDictionarySize(maximumDictionarySize).build(),
      ).use { archive ->
        require(!archive.isPasswordProtected) {
          "Password-protected RAR archives cannot be converted"
        }
        ZipOutputStream(Files.newOutputStream(destination).buffered()).use { output ->
          output.setLevel(Deflater.BEST_SPEED)
          archive.fileHeaders
            .asSequence()
            .filterNot { it.isDirectory }
            .forEach { header ->
              entryCount += 1
              require(entryCount <= maximumEntries) {
                "RAR archive exceeds the entry limit"
              }
              val name = header.fileName.safeArchiveEntryName()
              require(names.add(name)) { "RAR archive contains duplicate entry names: $name" }
              val expectedSize = header.fullUnpackSize
              require(expectedSize >= 0) { "RAR entry has an unknown expanded size: $name" }
              require(expandedBytes <= maximumExpandedBytes - expectedSize) {
                "RAR archive exceeds the expanded size limit"
              }
              output.putNextEntry(
                ZipEntry(name).apply {
                  header.lastModifiedTime?.toMillis()?.takeIf { it >= 0 }?.let(::setTime)
                },
              )
              var written = 0L
              archive.getInputStream(header).buffered().use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                  val read = input.read(buffer)
                  if (read < 0) break
                  if (read == 0) continue
                  written += read
                  require(written <= expectedSize) {
                    "RAR entry expanded beyond its declared size: $name"
                  }
                  output.write(buffer, 0, read)
                }
              }
              require(written == expectedSize) {
                "RAR entry expanded size does not match its header: $name"
              }
              output.closeEntry()
              expandedBytes += written
            }
        }
      }
      require(entryCount > 0) { "RAR archive does not contain files" }
      return ArchiveConversionResult(entryCount, expandedBytes)
    } catch (failure: Throwable) {
      Files.deleteIfExists(destination)
      throw failure
    }
  }

  private fun String.safeArchiveEntryName(): String {
    val normalized = replace('\\', '/')
    require(
      normalized.isNotBlank() &&
        !normalized.startsWith('/') &&
        normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
    ) {
      "RAR archive contains an unsafe entry name"
    }
    return normalized
  }

  companion object {
    const val DEFAULT_MAXIMUM_ENTRIES: Int = 100_000
    const val DEFAULT_MAXIMUM_EXPANDED_BYTES: Long = 1L shl 40
    private const val COPY_BUFFER_SIZE = 64 * 1_024
  }
}

data class ArchiveConversionResult(
  val entryCount: Int,
  val expandedBytes: Long,
) {
  init {
    require(entryCount > 0) { "Converted archive entry count must be positive" }
    require(expandedBytes >= 0) { "Converted archive size must not be negative" }
  }
}
