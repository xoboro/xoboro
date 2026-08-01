package io.xoboro.core.application

import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.SeriesId

/**
 * Decides what a series or read-list archive download contains, for one caller.
 *
 * Both the Xoboro-native surface and the Komga-compatible surface plan through this class instead of
 * listing members themselves. That is deliberate rather than tidiness: an archive route assembles
 * many media items from one identifier, so the authorization check that a single-item route cannot
 * omit - it has nothing to serve without it - is exactly the one an archive route can. Every member
 * here comes from a catalog read carrying the caller's [CatalogAccess], so a caller's library grants
 * and content restrictions filter the archive by construction and neither surface has its own copy of
 * the rule to keep in step.
 *
 * Restrictions are evaluated per media item rather than once for the source. A read list spans series
 * and libraries, so some of its items can be unreadable for a caller who may see the list itself; a
 * series is visible or not as a whole, but its items are filtered by the same read regardless, so the
 * two paths cannot drift apart.
 */
class MediaArchivePlanner(
  private val catalog: CatalogReadRepository,
) {
  /**
   * Plans a series archive, or `null` when the series does not exist or is not visible to the caller.
   *
   * Members are ordered by `numberSort`, the same order the catalog lists a series' items in, because
   * an archive of a series is read in that order and zip entries carry no other sequence. Deleted
   * items are excluded by [BookCatalogQuery]'s default: their files are gone, so including them would
   * make an archive fail part-written.
   */
  fun seriesArchive(
    seriesId: SeriesId,
    access: CatalogAccess,
  ): MediaArchivePlan? {
    val series = catalog.findSeriesByIdOrNull(seriesId, access) ?: return null
    val items =
      catalog
        .findBooks(
          query = BookCatalogQuery(seriesId = seriesId),
          access = access,
          page =
            CatalogPageRequest(
              sorts = listOf(CatalogSort(NUMBER_SORT_PROPERTY)),
              unpaged = true,
            ),
        ).content
    return MediaArchivePlan(
      fileName = mediaArchiveFileName(series.metadata.title),
      members = items.map { item -> MediaArchiveMember(item.book.id) },
    )
  }

  /**
   * Plans a read-list archive from a read list the caller has already resolved.
   *
   * The read list itself is taken as given because [io.xoboro.core.domain.ReadListRepository] has no
   * access-aware lookup: a read list is visible through the items in it, which is what this resolves.
   * A caller that must hide an empty result behind a not-found decides that from
   * [MediaArchivePlan.members]; a plan with no members is returned rather than `null` so the two
   * surfaces can answer that case differently without this class picking for them.
   *
   * Member order is the read list's own order, and each member is numbered by its position in that
   * list rather than in the filtered result.
   */
  fun readListArchive(
    readList: ReadList,
    access: CatalogAccess,
  ): MediaArchivePlan =
    MediaArchivePlan(
      fileName = mediaArchiveFileName(readList.name),
      members =
        readList.bookIds.mapIndexedNotNull { index, bookId ->
          catalog
            .findBookByIdOrNull(bookId, access)
            ?.let { item -> MediaArchiveMember(item.book.id, entryPrefix = index + 1) }
        },
    )

  private companion object {
    const val NUMBER_SORT_PROPERTY = "numberSort"
  }
}
