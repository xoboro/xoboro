package io.xoboro.server.sources.webdav

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** [WebDavMediaCache.peek] result: the validators to send on a conditional `GET`, without touching recency or pin state. */
data class WebDavCachedValidators(
  val etag: String?,
  val lastModifiedHttpDate: String?,
)

/**
 * An LRU disk cache for materialized WebDAV media, keyed by item URL and validated with the
 * ETag/Last-Modified the server sent for the cached copy - so an unchanged archive is downloaded
 * once and re-served from disk on every later `materialize` call.
 *
 * The index is in-memory only and is rebuilt empty on every process start; [cacheDirectory] is
 * cleared at construction so a restart never accumulates orphaned files the index has forgotten
 * about. This trades cross-restart persistence (a cold cache after every restart) for a cache
 * that can never leak disk space, which matters more for a bound the operator was promised is a
 * hard total.
 *
 * A cache entry is pinned (kept out of eviction) for as long as its [WebDavMaterializedMedia] is
 * open, via [acquire]/[release] reference counting - the same guarantee
 * [io.xoboro.server.media.MaterializedMedia]'s contract implies: a file backing an open handle is
 * never deleted out from under it. Bytes are only enforced when a new entry is [store]d; freeing
 * a handle does not retroactively re-check the bound, so eviction is "best effort on the way in"
 * rather than continuously enforced - the same trade every simple LRU cache with pinned entries
 * makes when eviction can't run because a caller is mid-read.
 */
class WebDavMediaCache(
  private val cacheDirectory: Path,
  private val maxTotalBytes: Long,
) {
  init {
    require(maxTotalBytes > 0) { "WebDAV cache byte bound must be positive" }
    Files.createDirectories(cacheDirectory)
    Files.list(cacheDirectory).use { entries -> entries.filter(Files::isRegularFile).forEach(Files::delete) }
  }

  private data class Entry(
    val path: Path,
    val etag: String?,
    val lastModifiedHttpDate: String?,
    val size: Long,
    var refCount: Int,
  )

  private val lock = Any()
  private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

  fun peek(key: String): WebDavCachedValidators? =
    synchronized(lock) {
      entries[key]?.let { WebDavCachedValidators(it.etag, it.lastModifiedHttpDate) }
    }

  /** Returns the cached path for [key], pinning it, or `null` if nothing is cached for it. */
  fun acquire(key: String): Path? =
    synchronized(lock) {
      entries[key]?.also { it.refCount += 1 }?.path
    }

  /** Records a freshly downloaded [tempFile] as the cached copy for [key], pinned once for the caller, then evicts if over budget. */
  fun store(
    key: String,
    tempFile: Path,
    etag: String?,
    lastModifiedHttpDate: String?,
    size: Long,
  ): Path =
    synchronized(lock) {
      val target = cacheFileFor(key)
      // A plain rename: any reader still holding the previous cached file open for this same key
      // keeps its file descriptor valid on the old inode, which POSIX guarantees survives a
      // rename onto its path.
      Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
      entries[key] = Entry(target, etag, lastModifiedHttpDate, size, refCount = 1)
      evictLocked()
      target
    }

  /** Unpins the entry for [key], if any. Safe to call after the entry has already been evicted. */
  fun release(key: String) {
    synchronized(lock) {
      entries[key]?.let { if (it.refCount > 0) it.refCount -= 1 }
    }
  }

  private fun evictLocked() {
    var total = entries.values.sumOf(Entry::size)
    if (total <= maxTotalBytes) return
    val candidates = entries.entries.iterator()
    while (total > maxTotalBytes && candidates.hasNext()) {
      val entry = candidates.next().value
      if (entry.refCount > 0) continue
      candidates.remove()
      Files.deleteIfExists(entry.path)
      total -= entry.size
    }
  }

  private fun cacheFileFor(key: String): Path {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return cacheDirectory.resolve("$hex.cache")
  }
}
