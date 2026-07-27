package io.xoboro.server.persistence

import io.xoboro.core.domain.Artwork
import io.xoboro.core.domain.ArtworkContent
import io.xoboro.core.domain.ArtworkId
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.ArtworkRepository
import io.xoboro.core.domain.ArtworkType
import org.jooq.Record

class JooqArtworkRepository(
  private val database: XoboroDatabase,
) : ArtworkRepository {
  override fun findAll(owner: ArtworkOwner): List<Artwork> =
    database.dsl
      .fetch(
        """
        SELECT artwork_thumbnail.*,
          CAST(file_size AS TEXT) AS file_size_64,
          CAST(created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
        FROM artwork_thumbnail
        WHERE owner_kind = ? AND owner_id = ?
        ORDER BY selected DESC, created_at_ms, id
        """.trimIndent(),
        owner.kind.name,
        owner.id,
      ).map { it.toArtwork() }

  override fun findByIdOrNull(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Artwork? =
    database.dsl
      .fetchOne(
        """
        SELECT artwork_thumbnail.*,
          CAST(file_size AS TEXT) AS file_size_64,
          CAST(created_at_ms AS TEXT) AS created_at_ms_64,
          CAST(updated_at_ms AS TEXT) AS updated_at_ms_64
        FROM artwork_thumbnail
        WHERE owner_kind = ? AND owner_id = ? AND id = ?
        """.trimIndent(),
        owner.kind.name,
        owner.id,
        id.value,
      )?.toArtwork()

  override fun findSelectedOrNull(owner: ArtworkOwner): Artwork? =
    findAll(owner).firstOrNull { it.selected }

  override fun content(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): ByteArray? =
    database.dsl
      .fetchOne(
        """
        SELECT content
        FROM artwork_thumbnail
        WHERE owner_kind = ? AND owner_id = ? AND id = ?
        """.trimIndent(),
        owner.kind.name,
        owner.id,
        id.value,
      )?.get("content", ByteArray::class.java)

  override fun insert(content: ArtworkContent) {
    database.transaction { transaction ->
      val item = content.artwork
      if (item.selected) {
        transaction.execute(
          """
          UPDATE artwork_thumbnail SET selected = 0, updated_at_ms = ?
          WHERE owner_kind = ? AND owner_id = ? AND selected = 1
          """.trimIndent(),
          item.updatedAtMillis,
          item.owner.kind.name,
          item.owner.id,
        )
      }
      transaction.execute(
        """
        INSERT INTO artwork_thumbnail (
          id, owner_kind, owner_id, artwork_type, selected, media_type,
          file_size, width, height, content, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        item.id.value,
        item.owner.kind.name,
        item.owner.id,
        item.type.name,
        item.selected.toSqliteInt(),
        item.mediaType,
        item.fileSize,
        item.width,
        item.height,
        content.bytes,
        item.createdAtMillis,
        item.updatedAtMillis,
      )
    }
  }

  override fun replaceGenerated(content: ArtworkContent) {
    require(content.artwork.type == ArtworkType.GENERATED) {
      "Replacement artwork must be generated"
    }
    database.transaction { transaction ->
      val item = content.artwork
      transaction.execute(
        """
        DELETE FROM artwork_thumbnail
        WHERE owner_kind = ? AND owner_id = ? AND artwork_type = ?
        """.trimIndent(),
        item.owner.kind.name,
        item.owner.id,
        ArtworkType.GENERATED.name,
      )
      if (item.selected) {
        transaction.execute(
          """
          UPDATE artwork_thumbnail SET selected = 0, updated_at_ms = ?
          WHERE owner_kind = ? AND owner_id = ? AND selected = 1
          """.trimIndent(),
          item.updatedAtMillis,
          item.owner.kind.name,
          item.owner.id,
        )
      }
      transaction.execute(
        """
        INSERT INTO artwork_thumbnail (
          id, owner_kind, owner_id, artwork_type, selected, media_type,
          file_size, width, height, content, created_at_ms, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        item.id.value,
        item.owner.kind.name,
        item.owner.id,
        item.type.name,
        item.selected.toSqliteInt(),
        item.mediaType,
        item.fileSize,
        item.width,
        item.height,
        content.bytes,
        item.createdAtMillis,
        item.updatedAtMillis,
      )
    }
  }

  override fun replaceSidecars(
    owner: ArtworkOwner,
    contents: List<ArtworkContent>,
  ) {
    require(contents.all { it.artwork.owner == owner }) {
      "Sidecar artwork owners must match"
    }
    require(contents.all { it.artwork.type == ArtworkType.SIDECAR }) {
      "Replacement artwork must be sidecar artwork"
    }
    require(contents.count { it.artwork.selected } <= 1) {
      "At most one sidecar artwork can be selected"
    }
    database.transaction { transaction ->
      transaction.execute(
        """
        DELETE FROM artwork_thumbnail
        WHERE owner_kind = ? AND owner_id = ? AND artwork_type = ?
        """.trimIndent(),
        owner.kind.name,
        owner.id,
        ArtworkType.SIDECAR.name,
      )
      if (contents.any { it.artwork.selected }) {
        val changedAt = contents.first().artwork.updatedAtMillis
        transaction.execute(
          """
          UPDATE artwork_thumbnail SET selected = 0, updated_at_ms = ?
          WHERE owner_kind = ? AND owner_id = ? AND selected = 1
          """.trimIndent(),
          changedAt,
          owner.kind.name,
          owner.id,
        )
      }
      contents.forEach { content ->
        val item = content.artwork
        transaction.execute(
          """
          INSERT INTO artwork_thumbnail (
            id, owner_kind, owner_id, artwork_type, selected, media_type,
            file_size, width, height, content, created_at_ms, updated_at_ms
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """.trimIndent(),
          item.id.value,
          owner.kind.name,
          owner.id,
          item.type.name,
          item.selected.toSqliteInt(),
          item.mediaType,
          item.fileSize,
          item.width,
          item.height,
          content.bytes,
          item.createdAtMillis,
          item.updatedAtMillis,
        )
      }
      val selectedExists =
        transaction.fetchExists(
          transaction
            .selectOne()
            .from("artwork_thumbnail")
            .where("owner_kind = ? AND owner_id = ? AND selected = 1", owner.kind.name, owner.id),
        )
      if (!selectedExists) {
        transaction.execute(
          """
          UPDATE artwork_thumbnail SET selected = 1
          WHERE id = (
            SELECT id FROM artwork_thumbnail
            WHERE owner_kind = ? AND owner_id = ?
            ORDER BY CASE artwork_type
              WHEN 'USER_UPLOADED' THEN 0
              WHEN 'SIDECAR' THEN 1
              ELSE 2
            END, created_at_ms, id
            LIMIT 1
          )
          """.trimIndent(),
          owner.kind.name,
          owner.id,
        )
      }
    }
  }

  override fun markSelected(
    owner: ArtworkOwner,
    id: ArtworkId,
    updatedAtMillis: Long,
  ): Boolean =
    database.transaction { transaction ->
      val exists =
        transaction.fetchExists(
          transaction
            .selectOne()
            .from("artwork_thumbnail")
            .where(
              "owner_kind = ? AND owner_id = ? AND id = ?",
              owner.kind.name,
              owner.id,
              id.value,
            ),
        )
      if (!exists) {
        false
      } else {
        transaction.execute(
          """
          UPDATE artwork_thumbnail SET selected = 0, updated_at_ms = ?
          WHERE owner_kind = ? AND owner_id = ?
          """.trimIndent(),
          updatedAtMillis,
          owner.kind.name,
          owner.id,
        )
        transaction.execute(
          """
          UPDATE artwork_thumbnail SET selected = 1, updated_at_ms = ?
          WHERE owner_kind = ? AND owner_id = ? AND id = ?
          """.trimIndent(),
          updatedAtMillis,
          owner.kind.name,
          owner.id,
          id.value,
        )
        true
      }
    }

  override fun delete(
    owner: ArtworkOwner,
    id: ArtworkId,
  ): Boolean =
    database.dsl.execute(
      """
      DELETE FROM artwork_thumbnail
      WHERE owner_kind = ? AND owner_id = ? AND id = ?
      """.trimIndent(),
      owner.kind.name,
      owner.id,
      id.value,
    ) == 1

  private fun Record.toArtwork(): Artwork =
    Artwork(
      id = ArtworkId(requiredString("id")),
      owner =
        ArtworkOwner(
          kind = ArtworkOwnerKind.valueOf(requiredString("owner_kind")),
          id = requiredString("owner_id"),
        ),
      type = ArtworkType.valueOf(requiredString("artwork_type")),
      selected = requiredBoolean("selected"),
      mediaType = requiredString("media_type"),
      fileSize = requiredLongText("file_size_64"),
      width = requiredInt("width"),
      height = requiredInt("height"),
      createdAtMillis = requiredLongText("created_at_ms_64"),
      updatedAtMillis = requiredLongText("updated_at_ms_64"),
    )

  private fun Record.requiredString(field: String): String =
    requireNotNull(get(field, String::class.java))

  private fun Record.requiredInt(field: String): Int =
    requireNotNull(get(field, Int::class.java))

  private fun Record.requiredLongText(field: String): Long =
    requireNotNull(get(field, String::class.java)).toLong()

  private fun Record.requiredBoolean(field: String): Boolean =
    requiredInt(field) == 1

  private fun Boolean.toSqliteInt(): Int = if (this) 1 else 0
}
