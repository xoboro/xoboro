package io.xoboro.compatibility.komga.differential

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface DifferentialHttpTransport {
  fun execute(
    baseUrl: String,
    request: DifferentialCase,
    authorization: String?,
  ): HttpSnapshot
}

class JdkDifferentialHttpTransport(
  private val client: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build(),
  private val requestTimeout: Duration = Duration.ofSeconds(30),
) : DifferentialHttpTransport {
  override fun execute(
    baseUrl: String,
    request: DifferentialCase,
    authorization: String?,
  ): HttpSnapshot {
    val builder =
      HttpRequest
        .newBuilder(resolve(baseUrl, request.path))
        .timeout(requestTimeout)
        .method(
          request.method,
          request.body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody(),
        )
    request.headers.forEach(builder::header)
    if (authorization != null && request.headers.keys.none { it.equals("Authorization", true) }) {
      builder.header("Authorization", authorization)
    }
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    return HttpSnapshot(
      status = response.statusCode(),
      headers = response.headers().map(),
      body = response.body(),
    )
  }

  private fun resolve(
    baseUrl: String,
    path: String,
  ): URI {
    val base = URI.create(baseUrl.trimEnd('/') + "/")
    require(base.scheme == "http" || base.scheme == "https") {
      "Differential base URL must use HTTP or HTTPS"
    }
    require(base.userInfo == null && base.query == null && base.fragment == null) {
      "Differential base URL must not contain credentials, query, or fragment"
    }
    return base.resolve(path.removePrefix("/"))
  }
}
