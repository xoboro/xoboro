package io.xoboro.server.persistence

import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkContent
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JooqArtworkRepositoryTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `persists owner scoped content and atomically changes selection`() {
    val path = tempDirectory.resolve("artwork.sqlite")
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqArtworkRepository(database)
      val first = content("first", OWNER, selected = true, byte = 1)
      val second = content("second", OWNER, selected = true, byte = 2)
      val other = content("other", OTHER_OWNER, selected = true, byte = 3)

      repository.insert(first)
      repository.insert(second)
      repository.insert(other)

      assertEquals(
        listOf("second", "first"),
        repository.findAll(OWNER).map { it.id.value },
      )
      assertFalse(requireNotNull(repository.findByIdOrNull(OWNER, first.artwork.id)).selected)
      assertTrue(requireNotNull(repository.findByIdOrNull(OWNER, second.artwork.id)).selected)
      assertContentEquals(second.bytes, repository.content(OWNER, second.artwork.id))
      assertNull(repository.findByIdOrNull(OWNER, other.artwork.id))
      assertTrue(repository.markSelected(OWNER, first.artwork.id, 30))
      assertEquals("first", repository.findSelectedOrNull(OWNER)?.id?.value)
      assertFalse(repository.markSelected(OWNER, other.artwork.id, 30))
      assertTrue(repository.delete(OWNER, second.artwork.id))
    }
    XoboroDatabase.open(DatabaseConfig(path)).use { database ->
      val repository = JooqArtworkRepository(database)
      assertEquals("first", repository.findSelectedOrNull(OWNER)?.id?.value)
      assertContentEquals(byteArrayOf(1), repository.content(OWNER, ArtworkId("first")))
    }
  }

  private fun content(
    id: String,
    owner: ArtworkOwner,
    selected: Boolean,
    byte: Byte,
  ): ArtworkContent =
    ArtworkContent(
      artwork =
        Artwork(
          id = ArtworkId(id),
          owner = owner,
          type = ArtworkType.USER_UPLOADED,
          selected = selected,
          mediaType = "image/jpeg",
          fileSize = 1,
          width = 1,
          height = 1,
          createdAtMillis = 10,
          updatedAtMillis = 10,
        ),
      bytes = byteArrayOf(byte),
    )

  private companion object {
    val OWNER = ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, "item-1")
    val OTHER_OWNER = ArtworkOwner(ArtworkOwnerKind.SERIES, "series-1")
  }
}
