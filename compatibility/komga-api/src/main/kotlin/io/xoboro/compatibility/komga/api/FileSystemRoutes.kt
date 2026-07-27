package io.xoboro.compatibility.komga.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.xoboro.core.domain.UserRole
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

fun Route.komgaFileSystemRoutes() {
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    post("/api/v1/filesystem") {
      val principal = requireNotNull(call.principal<KomgaPrincipal>())
      if (UserRole.ADMIN !in principal.user.roles) {
        call.respond(HttpStatusCode.Forbidden)
        return@post
      }
      val request =
        if (call.request.contentLength() == 0L) {
          DirectoryRequestDto()
        } else {
          call.receive<DirectoryRequestDto>()
        }
      if (request.path.isBlank()) {
        call.respond(
          DirectoryListingDto(
            directories = FileSystems.getDefault().rootDirectories.map(Path::toDto),
            files = emptyList(),
          ),
        )
        return@post
      }
      val requested =
        try {
          Path.of(request.path)
        } catch (_: Exception) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path does not exist"))
          return@post
        }
      if (!requested.isAbsolute) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path must be absolute"))
        return@post
      }
      val directory = if (Files.isDirectory(requested)) requested else requested.parent
      val children =
        try {
          requireNotNull(directory)
          Files.list(directory).use { paths ->
            paths
              .filter { path ->
                !Files.isHidden(path) && (request.showFiles || Files.isDirectory(path))
              }
              .sorted(compareBy(String.CASE_INSENSITIVE_ORDER) { it.toString() })
              .map(Path::toDto)
              .toList()
          }
        } catch (_: Exception) {
          call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Path does not exist"))
          return@post
        }
      val (directories, files) = children.partition { it.type == "directory" }
      call.respond(
        DirectoryListingDto(
          parent = requested.parent?.toString() ?: "",
          directories = directories,
          files = files,
        ),
      )
    }
  }
}

@Serializable
data class DirectoryRequestDto(
  val path: String = "",
  val showFiles: Boolean = false,
)

@Serializable
data class DirectoryListingDto(
  val parent: String? = null,
  val directories: List<PathDto>,
  val files: List<PathDto>,
)

@Serializable
data class PathDto(
  val type: String,
  val name: String,
  val path: String,
)

private fun Path.toDto(): PathDto =
  PathDto(
    type = if (Files.isDirectory(this)) "directory" else "file",
    name = (fileName ?: this).toString(),
    path = toString(),
  )
