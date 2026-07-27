package io.xoboro.core.application

import io.xoboro.core.domain.ReadListRepository

data class ReadListImportRequest(
  val name: String,
  val books: List<ReadListImportBookRequest>,
)

data class ReadListImportBookRequest(
  val series: Set<String>,
  val number: String,
)

data class ReadListImportMatch(
  val readList: ReadListNameMatch,
  val requests: List<ReadListImportBookMatches>,
  val errorCode: String = "",
)

data class ReadListNameMatch(
  val name: String,
  val errorCode: String = "",
)

data class ReadListImportBookMatches(
  val request: ReadListImportBookRequest,
  val matches: List<ReadListImportSeriesMatch>,
)

data class ReadListImportSeriesMatch(
  val seriesId: String,
  val title: String,
  val releaseDate: String?,
  val books: List<ReadListImportBookMatch>,
)

data class ReadListImportBookMatch(
  val bookId: String,
  val number: String,
  val title: String,
)

class ReadListImportException(
  val code: String,
) : IllegalArgumentException(code) {
  init {
    require(code.isNotBlank()) { "Read-list import error code must not be blank" }
  }
}

fun interface ReadListImportParser {
  fun parse(bytes: ByteArray): ReadListImportRequest
}

fun interface ReadListImportMatcher {
  fun match(requests: List<ReadListImportBookRequest>): List<ReadListImportBookMatches>
}

class ReadListImportLifecycle(
  private val parser: ReadListImportParser,
  private val matcher: ReadListImportMatcher,
  private val readLists: ReadListRepository,
) {
  fun match(bytes: ByteArray): ReadListImportMatch {
    require(bytes.isNotEmpty() && bytes.size <= MAXIMUM_UPLOAD_BYTES) {
      "ERR_1015"
    }
    val request = parser.parse(bytes)
    return ReadListImportMatch(
      readList =
        ReadListNameMatch(
          name = request.name,
          errorCode =
            if (readLists.findByNameIgnoreCaseOrNull(request.name) == null) {
              ""
            } else {
              "ERR_1009"
            },
        ),
      requests = matcher.match(request.books),
    )
  }

  companion object {
    const val MAXIMUM_UPLOAD_BYTES: Int = 5 * 1_024 * 1_024
  }
}
