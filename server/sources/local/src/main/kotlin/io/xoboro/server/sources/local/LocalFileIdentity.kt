package io.xoboro.server.sources.local

import java.nio.file.attribute.BasicFileAttributes

/**
 * The handle a scan stores to recognise a file that moved to a new path.
 *
 * [BasicFileAttributes.fileKey] renders as `(dev=<hex>,ino=<decimal>)` on Unix, and the device number
 * belongs to the mount rather than to the file: mounting the filesystem again changes it, and
 * recreating a container is enough to do that. Storing the whole rendering therefore made every file
 * in the library report a new handle after a remount — measured at 111,745 of 120,723 books in one
 * library — which is a difference the catalogue then has to write down for each of them.
 *
 * The inode is the part that actually identifies the file, and it survives the remount, so that is
 * what we keep. It also has the uniqueness the move detection asks for: inodes are unique within a
 * filesystem, and a library spanning two filesystems can collide, which is precisely the case the
 * detection already discards as ambiguous.
 *
 * The rendering is not a documented format. A key that does not parse is kept whole rather than
 * dropped, because an unstable handle is still worth more than none.
 */
internal fun BasicFileAttributes.durableIdentity(): String? {
  val key = fileKey()?.toString() ?: return null
  return INODE.find(key)?.groupValues?.get(1) ?: key
}

private val INODE = Regex("""\bino=(\d+)""")
