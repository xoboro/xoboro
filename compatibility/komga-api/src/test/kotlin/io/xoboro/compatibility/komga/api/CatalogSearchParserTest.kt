package io.xoboro.compatibility.komga.api

import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CatalogSearchParserTest {
  @Test
  fun `parses bounded recursive book conditions and object matches`() {
    val search =
      JSON.parseToJsonElement(
        """
        {
          "condition": {
            "allOf": [
              {"seriesId": {"operator": "is", "value": "series-1"}},
              {
                "anyOf": [
                  {"numberSort": {"operator": "greaterThan", "value": 2}},
                  {
                    "author": {
                      "operator": "is",
                      "value": {"name": "Morgan Example", "role": "writer"}
                    }
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent(),
      ).jsonObject

    val parsed = search.parseCatalogSearchCondition(CatalogSearchTarget.BOOK)

    val all = parsed as CatalogSearchCondition.AllOf
    assertEquals(2, all.conditions.size)
    assertEquals(
      CatalogSearchCondition.Predicate(
        CatalogSearchField.SERIES_ID,
        CatalogSearchOperator.IS,
        "series-1",
      ),
      all.conditions.first(),
    )
    val author =
      ((all.conditions.last() as CatalogSearchCondition.AnyOf).conditions.last()
        as CatalogSearchCondition.Predicate)
    assertEquals(CatalogSearchField.AUTHOR, author.field)
    assertEquals(mapOf("name" to "Morgan Example", "role" to "writer"), author.attributes)
  }

  @Test
  fun `rejects target mismatches invalid operators dates and empty groups`() {
    listOf(
      """{"condition":{"mediaStatus":{"operator":"is","value":"READY"}}}""",
      """{"condition":{"allOf":[]}}""",
      """{"condition":{"releaseDate":{"operator":"before","dateTime":"invalid"}}}""",
      """{"condition":{"oneShot":{"operator":"contains","value":"true"}}}""",
    ).forEach { payload ->
      assertFailsWith<IllegalArgumentException> {
        JSON
          .parseToJsonElement(payload)
          .jsonObject
          .parseCatalogSearchCondition(CatalogSearchTarget.SERIES)
      }
    }
  }

  companion object {
    private val JSON = Json
  }
}
