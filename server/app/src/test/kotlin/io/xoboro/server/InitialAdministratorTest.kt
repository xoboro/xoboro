package io.xoboro.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class InitialAdministratorTest {
  @TempDir
  lateinit var temporaryDirectory: Path

  @Test
  fun `is absent when nothing is configured`() {
    assertNull(InitialAdministrator.fromEnvironment(emptyMap()))
    assertNull(
      InitialAdministrator.fromEnvironment(
        mapOf(
          InitialAdministrator.EMAIL_KEY to "  ",
          InitialAdministrator.PASSWORD_KEY to "",
        ),
      ),
    )
  }

  @Test
  fun `reads the environment form`() {
    val resolved =
      InitialAdministrator.fromEnvironment(
        mapOf(
          InitialAdministrator.EMAIL_KEY to "admin@example.invalid",
          InitialAdministrator.PASSWORD_KEY to "synthetic-password",
        ),
      )

    assertEquals(InitialAdministrator("admin@example.invalid", "synthetic-password"), resolved)
  }

  @Test
  fun `prefers the file form over the environment form`() {
    val file = temporaryDirectory.resolve("password")
    Files.writeString(file, "from-the-file\n")

    val resolved =
      InitialAdministrator.fromEnvironment(
        mapOf(
          InitialAdministrator.EMAIL_KEY to "admin@example.invalid",
          InitialAdministrator.PASSWORD_KEY to "from-the-environment",
          InitialAdministrator.PASSWORD_FILE_KEY to file.toString(),
        ),
      )

    // A password in the environment is readable from /proc, appears in `docker inspect`, and gets
    // committed in the Compose file that sets it. When both are given the file wins.
    assertEquals("from-the-file", resolved?.password)
  }

  @Test
  fun `strips only the trailing newline from a password file`() {
    listOf("secret\n", "secret\r\n", "secret").forEach { contents ->
      val file = temporaryDirectory.resolve("password-${contents.length}")
      Files.writeString(file, contents)

      assertEquals(
        "secret",
        InitialAdministrator.fromEnvironment(
          mapOf(
            InitialAdministrator.EMAIL_KEY to "admin@example.invalid",
            InitialAdministrator.PASSWORD_FILE_KEY to file.toString(),
          ),
        )?.password,
      )
    }

    // Not trimmed: a password may legitimately contain leading or inner whitespace, and trimming would
    // silently change the secret rather than reject a malformed file.
    val padded = temporaryDirectory.resolve("padded")
    Files.writeString(padded, "  spaced secret  \n")
    assertEquals(
      "  spaced secret  ",
      InitialAdministrator.fromEnvironment(
        mapOf(
          InitialAdministrator.EMAIL_KEY to "admin@example.invalid",
          InitialAdministrator.PASSWORD_FILE_KEY to padded.toString(),
        ),
      )?.password,
    )
  }

  @Test
  fun `fails when only one half is configured`() {
    // Not a silent skip. An operator who set one half expected provisioning to happen, and a server
    // that quietly came up unclaimed instead would be reachable by whoever found it first.
    assertFailsWith<IllegalArgumentException> {
      InitialAdministrator.fromEnvironment(
        mapOf(InitialAdministrator.EMAIL_KEY to "admin@example.invalid"),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      InitialAdministrator.fromEnvironment(
        mapOf(InitialAdministrator.PASSWORD_KEY to "synthetic-password"),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      InitialAdministrator.fromEnvironment(
        mapOf(InitialAdministrator.PASSWORD_FILE_KEY to "/nonexistent"),
      )
    }
  }

  @Test
  fun `fails when a configured password file is unreadable or empty`() {
    // A secret mount that failed is not the same as "no secret configured". Treating them alike would
    // turn a broken deployment into an open one.
    assertFailsWith<IllegalArgumentException> {
      InitialAdministrator.fromEnvironment(
        mapOf(
          InitialAdministrator.EMAIL_KEY to "admin@example.invalid",
          InitialAdministrator.PASSWORD_FILE_KEY to
            temporaryDirectory.resolve("missing").toString(),
        ),
      )
    }

    val empty = temporaryDirectory.resolve("empty")
    Files.writeString(empty, "\n")
    assertFailsWith<IllegalArgumentException> {
      InitialAdministrator.fromEnvironment(
        mapOf(
          InitialAdministrator.EMAIL_KEY to "admin@example.invalid",
          InitialAdministrator.PASSWORD_FILE_KEY to empty.toString(),
        ),
      )
    }
  }

  @Test
  fun `never renders the password`() {
    val rendered = InitialAdministrator("admin@example.invalid", "synthetic-password").toString()

    // ServerConfig holds one of these, and a config object is exactly what ends up in a log line or an
    // exception message.
    assertFalse("synthetic-password" in rendered, "toString disclosed the password")
    assertEquals(true, "[REDACTED]" in rendered)
    assertEquals(true, "admin@example.invalid" in rendered)
  }

  @Test
  fun `never renders the password through the whole config`() {
    val config =
      ServerConfig(
        port = 25_620,
        databasePath = temporaryDirectory.resolve("config.sqlite"),
        workerCount = 1,
        taskPollMillis = 10,
        taskFailurePollMillis = 10,
        taskLeaseMillis = 1_000,
        shutdownTimeoutMillis = 1_000,
        initialAdministrator =
          InitialAdministrator("admin@example.invalid", "synthetic-password"),
      )

    assertFalse("synthetic-password" in config.toString(), "ServerConfig disclosed the password")
  }
}
