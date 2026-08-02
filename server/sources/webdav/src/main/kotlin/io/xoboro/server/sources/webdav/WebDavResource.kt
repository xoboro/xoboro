package io.xoboro.server.sources.webdav

/**
 * One `<D:response>` entry from a `207 Multi-Status` PROPFIND body, already resolved to an
 * absolute URL against the request that produced it.
 *
 * [contentLength] and [lastModifiedHttpDate] are nullable because a server is free to omit
 * `getcontentlength`/`getlastmodified` from its 200-status propstat - callers decide the fallback
 * (see `WebDavSourceInventory`, which reports an absent length as `0` and an absent timestamp as
 * epoch, rather than failing the whole listing over one incomplete entry).
 */
data class WebDavResource(
  val url: String,
  val isCollection: Boolean,
  val contentLength: Long?,
  val lastModifiedHttpDate: String?,
  val etag: String?,
)
