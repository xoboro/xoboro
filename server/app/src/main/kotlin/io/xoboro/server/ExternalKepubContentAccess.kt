package io.xoboro.server

import io.xoboro.compatibility.komga.api.KepubContentAccess
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.MediaContentStream
import io.xoboro.core.domain.BookId
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ExternalKepubContentAccess(
  private val books: BookContentAccess,
  private val executablePath: () -> String?,
  private val cacheDirectory: Path,
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : KepubContentAccess {
  private val locks = ConcurrentHashMap<String, Any>()

  init {
    require(timeoutMillis > 0) { "KEPUB conversion timeout must be positive" }
    Files.createDirectories(cacheDirectory)
  }

  override fun isAvailable(): Boolean = executable() != null

  override fun openKepub(
    bookId: BookId,
    revision: String,
  ): MediaContentStream? {
    require(revision.isNotBlank()) { "KEPUB revision must not be blank" }
    val executable = executable() ?: return null
    val bookKey = bookId.value.sha256()
    val cacheKey = "$bookKey-${revision.sha256()}"
    val target = cacheDirectory.resolve("$cacheKey.kepub.epub")
    val lock = locks.computeIfAbsent(cacheKey) { Any() }
    try {
      synchronized(lock) {
        if (!Files.isRegularFile(target) || Files.size(target) == 0L) {
          convert(executable, bookId, target)
          if (Files.isRegularFile(target)) {
            cacheDirectory.removeOtherRevisions(bookKey, target)
          }
        }
      }
    } finally {
      locks.remove(cacheKey, lock)
    }
    return if (Files.isRegularFile(target)) PathContentStream(target) else null
  }

  private fun executable(): Path? =
    executablePath()
      ?.takeIf(String::isNotBlank)
      ?.let(Path::of)
      ?.toAbsolutePath()
      ?.normalize()
      ?.takeIf(Files::isRegularFile)
      ?.takeIf(Files::isExecutable)

  private fun convert(
    executable: Path,
    bookId: BookId,
    target: Path,
  ) {
    val input = Files.createTempFile(cacheDirectory, ".kepub-input-", ".epub")
    val output = Files.createTempFile(cacheDirectory, ".kepub-output-", ".kepub.epub")
    Files.deleteIfExists(output)
    try {
      val source = books.openBook(bookId) ?: return
      try {
        Files.newOutputStream(input, StandardOpenOption.TRUNCATE_EXISTING).use { destination ->
          source.copyTo(destination)
        }
      } finally {
        source.close()
      }
      val process =
        ProcessBuilder(
          executable.toString(),
          "--output",
          output.toString(),
          input.toString(),
        ).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
      val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
      if (!completed) {
        process.destroyForcibly()
        process.waitFor()
        return
      }
      if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0L) {
        return
      }
      runCatching {
        Files.move(
          output,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      }.getOrElse {
        Files.move(output, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(input)
      Files.deleteIfExists(output)
    }
  }

  private fun MediaContentStream.copyTo(destination: OutputStream) {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    while (true) {
      val count = read(buffer)
      if (count < 0) break
      if (count > 0) destination.write(buffer, 0, count)
    }
  }

  private fun Path.removeOtherRevisions(
    bookKey: String,
    current: Path,
  ) {
    Files.list(this).use { paths ->
      paths
        .filter { it != current && it.fileName.toString().startsWith("$bookKey-") }
        .forEach { Files.deleteIfExists(it) }
    }
  }

  private class PathContentStream(
    private val path: Path,
  ) : MediaContentStream {
    private val input: InputStream = Files.newInputStream(path)
    override val fileName: String = path.fileName.toString()
    override val mediaType: String = "application/epub+zip"
    override val contentLength: Long = Files.size(path)

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int = input.read(buffer, offset, length)

    override fun skip(byteCount: Long): Long = input.skip(byteCount)

    override fun close() = input.close()
  }

  private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
      .digest(encodeToByteArray())
      .joinToString("") { "%02x".format(it) }

  companion object {
    const val DEFAULT_TIMEOUT_MILLIS: Long = 120_000
    private const val STREAM_BUFFER_SIZE = 64 * 1_024
  }
}
