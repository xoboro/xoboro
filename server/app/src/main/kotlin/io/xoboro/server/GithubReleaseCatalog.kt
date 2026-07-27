package io.xoboro.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.xoboro.core.application.ServerRelease
import io.xoboro.core.application.ServerReleaseCatalog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class GithubReleaseCatalog(
  private val client: HttpClient,
  private val currentTimeMillis: () -> Long = System::currentTimeMillis,
  private val endpoint: String = DEFAULT_ENDPOINT,
) : ServerReleaseCatalog {
  private val mutex = Mutex()
  private var cachedAtMillis: Long = Long.MIN_VALUE
  private var cached: List<ServerRelease>? = null

  override suspend fun releases(): List<ServerRelease> {
    val now = currentTimeMillis()
    cached?.takeIf { now - cachedAtMillis in 0 until CACHE_MILLIS }?.let { return it }
    return mutex.withLock {
      val lockedNow = currentTimeMillis()
      cached?.takeIf { lockedNow - cachedAtMillis in 0 until CACHE_MILLIS }?.let {
        return@withLock it
      }
      client
        .get(endpoint) {
          parameter("per_page", 20)
        }.body<List<GithubRelease>>()
        .mapIndexed { index, release ->
          ServerRelease(
            version = release.tagName,
            releaseDate = release.publishedAt,
            url = release.htmlUrl,
            latest = index == 0,
            preRelease = release.preRelease,
            description = release.body,
          )
        }.also {
          cached = it
          cachedAtMillis = lockedNow
        }
    }
  }

  @Serializable
  private data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("published_at")
    val publishedAt: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("prerelease")
    val preRelease: Boolean,
    val body: String,
  )

  private companion object {
    const val DEFAULT_ENDPOINT = "https://api.github.com/repos/xoboro/xoboro/releases"
    const val CACHE_MILLIS = 60L * 60 * 1_000
  }
}
