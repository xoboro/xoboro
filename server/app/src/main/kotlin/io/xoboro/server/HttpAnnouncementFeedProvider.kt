package io.xoboro.server

import io.xoboro.core.domain.AnnouncementAuthor
import io.xoboro.core.domain.AnnouncementFeed
import io.xoboro.core.domain.AnnouncementFeedProvider
import io.xoboro.core.domain.AnnouncementItem
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class HttpAnnouncementFeedProvider(
  private val fetchContent: suspend () -> String?,
  private val currentTimeMillis: () -> Long,
  private val cacheTimeoutMillis: Long = DEFAULT_CACHE_TIMEOUT_MILLIS,
) : AnnouncementFeedProvider {
  private val refreshMutex = Mutex()

  @Volatile
  private var cached: CacheEntry? = null

  init {
    require(cacheTimeoutMillis > 0) { "Announcement cache timeout must be positive" }
  }

  override suspend fun fetch(): AnnouncementFeed? {
    val now = currentTimeMillis()
    require(now >= 0) { "Announcement timestamp must not be negative" }
    cached?.takeIf { it.isFreshAt(now) }?.let { entry ->
      cached = entry.copy(lastAccessMillis = now)
      return entry.feed
    }
    return refreshMutex.withLock {
      val refreshTime = currentTimeMillis()
      require(refreshTime >= 0) { "Announcement timestamp must not be negative" }
      cached?.takeIf { it.isFreshAt(refreshTime) }?.let { entry ->
        cached = entry.copy(lastAccessMillis = refreshTime)
        return@withLock entry.feed
      }
      val content = fetchContent() ?: return@withLock null
      val feed = WIRE_JSON.decodeFromString<RemoteAnnouncementFeed>(content).toDomain()
      cached = CacheEntry(feed, refreshTime)
      feed
    }
  }

  private data class CacheEntry(
    val feed: AnnouncementFeed,
    val lastAccessMillis: Long,
  )

  private fun CacheEntry.isFreshAt(now: Long): Boolean =
    now >= lastAccessMillis && now - lastAccessMillis < cacheTimeoutMillis

  companion object {
    const val DEFAULT_CACHE_TIMEOUT_MILLIS: Long = 60L * 60 * 1_000
    private val WIRE_JSON = Json { ignoreUnknownKeys = true }

    fun komgaCompatible(): HttpAnnouncementFeedProvider {
      val client =
        HttpClient
          .newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build()
      val request =
        HttpRequest
          .newBuilder(URI(KOMGA_ANNOUNCEMENT_FEED))
          .timeout(Duration.ofSeconds(20))
          .header("Accept", "application/feed+json, application/json")
          .GET()
          .build()
      return HttpAnnouncementFeedProvider(
        fetchContent = {
          withContext(Dispatchers.IO) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
              "Announcement feed returned HTTP ${response.statusCode()}"
            }
            response.body()
          }
        },
        currentTimeMillis = System::currentTimeMillis,
      )
    }
  }
}

@Serializable
private data class RemoteAnnouncementFeed(
  val version: String,
  val title: String,
  @SerialName("home_page_url")
  val homePageUrl: String? = null,
  val description: String? = null,
  val items: List<RemoteAnnouncementItem> = emptyList(),
) {
  fun toDomain(): AnnouncementFeed =
    AnnouncementFeed(
      version = version,
      title = title,
      homePageUrl = homePageUrl,
      description = description,
      items = items.map(RemoteAnnouncementItem::toDomain),
    )
}

@Serializable
private data class RemoteAnnouncementItem(
  val id: String,
  val url: String? = null,
  val title: String? = null,
  val summary: String? = null,
  @SerialName("content_html")
  val contentHtml: String? = null,
  @SerialName("date_modified")
  val dateModified: String? = null,
  val author: RemoteAnnouncementAuthor? = null,
  val tags: Set<String> = emptySet(),
) {
  fun toDomain(): AnnouncementItem =
    AnnouncementItem(
      id = id,
      url = url,
      title = title,
      summary = summary,
      contentHtml = contentHtml,
      dateModified = dateModified,
      author = author?.let { AnnouncementAuthor(it.name, it.url) },
      tags = tags,
    )
}

@Serializable
private data class RemoteAnnouncementAuthor(
  val name: String? = null,
  val url: String? = null,
)

private const val KOMGA_ANNOUNCEMENT_FEED = "https://komga.org/blog/feed.json"
