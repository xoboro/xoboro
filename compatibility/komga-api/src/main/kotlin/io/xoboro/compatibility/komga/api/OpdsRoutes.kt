package io.xoboro.compatibility.komga.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.xoboro.core.application.ArtworkLifecycle
import io.xoboro.core.application.BookCatalogQuery
import io.xoboro.core.application.BookContentAccess
import io.xoboro.core.application.CatalogBook
import io.xoboro.core.application.CatalogPage
import io.xoboro.core.application.CatalogPageRequest
import io.xoboro.core.application.CatalogReadRepository
import io.xoboro.core.application.CatalogSearchCondition
import io.xoboro.core.application.CatalogSearchField
import io.xoboro.core.application.CatalogSearchOperator
import io.xoboro.core.application.CatalogSeries
import io.xoboro.core.application.CatalogSort
import io.xoboro.core.application.CatalogSortDirection
import io.xoboro.core.application.MetadataFacet
import io.xoboro.core.application.MetadataFacetQuery
import io.xoboro.core.application.MetadataFacetRepository
import io.xoboro.core.application.PageImageFormat
import io.xoboro.core.application.PageImageRequest
import io.xoboro.core.application.ReadProgressLifecycle
import io.xoboro.core.application.SeriesCatalogQuery
import io.xoboro.core.domain.ArtworkOwner
import io.xoboro.core.domain.ArtworkOwnerKind
import io.xoboro.core.domain.BookId
import io.xoboro.core.domain.CollectionId
import io.xoboro.core.domain.Library
import io.xoboro.core.domain.LibraryId
import io.xoboro.core.domain.LibraryRepository
import io.xoboro.core.domain.MediaProfile
import io.xoboro.core.domain.ReadList
import io.xoboro.core.domain.ReadListId
import io.xoboro.core.domain.ReadListRepository
import io.xoboro.core.domain.SeriesCollection
import io.xoboro.core.domain.SeriesCollectionRepository
import io.xoboro.core.domain.SeriesId
import io.xoboro.core.domain.User
import io.xoboro.core.domain.UserRole
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.komgaOpdsRoutes(
  catalog: CatalogReadRepository,
  libraries: LibraryRepository,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
  progress: ReadProgressLifecycle,
  facets: MetadataFacetRepository? = null,
) {
  get("/opds/v2/auth") {
    call.respondOpds(
      OpdsAuthenticationDocumentDto(
        authentication =
          listOf(
            OpdsAuthenticationFlowDto(
              labels = OpdsAuthenticationLabelsDto("Email", "Password"),
            ),
          ),
        title = "Komga",
        id = call.opdsUrl("/opds/v2/auth"),
        description = "Enter your email and password to authenticate.",
        links =
          listOf(
            WPLinkDto(rel = "help", href = "https://komga.org"),
            WPLinkDto(rel = "logo", href = call.opdsUrl("/android-chrome-512x512.png")),
          ),
      ),
      OPDS_AUTH_CONTENT_TYPE,
    )
  }
  authenticate(
    KOMGA_BASIC_AUTHENTICATION,
    KOMGA_API_KEY_AUTHENTICATION,
    KOMGA_SESSION_AUTHENTICATION,
    KOMGA_REMEMBER_ME_AUTHENTICATION,
    strategy = AuthenticationStrategy.FirstSuccessful,
  ) {
    opdsV1Routes(catalog, libraries, collections, readLists, artwork, content)
    opdsV2Routes(catalog, libraries, collections, readLists, artwork, content, facets)
    opdsV2ManifestRoutes(catalog, progress)
  }
}

private fun Route.opdsV1Routes(
  catalog: CatalogReadRepository,
  libraries: LibraryRepository,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
) {
  get("/opds/v1.2/catalog") {
    val entries =
      listOf(
        AtomEntry("keepReading", "Keep Reading", "Continue reading your in progress books", "/opds/v1.2/keep-reading"),
        AtomEntry("ondeck", "On Deck", "Browse what to read next", "/opds/v1.2/ondeck"),
        AtomEntry("allSeries", "All series", "Browse by series", "/opds/v1.2/series"),
        AtomEntry("latestSeries", "Latest series", "Browse latest series", "/opds/v1.2/series/latest"),
        AtomEntry("latestBooks", "Latest books", "Browse latest books", "/opds/v1.2/books/latest"),
        AtomEntry("allLibraries", "All libraries", "Browse by library", "/opds/v1.2/libraries"),
        AtomEntry("allCollections", "All collections", "Browse by collection", "/opds/v1.2/collections"),
        AtomEntry("allReadLists", "All read lists", "Browse by read lists", "/opds/v1.2/readlists"),
        AtomEntry("allPublishers", "All publishers", "Browse by publishers", "/opds/v1.2/publishers"),
      )
    call.respondAtom("root", "Komga OPDS catalog", entries)
  }
  get("/opds/v1.2/search") {
    val template = call.opdsUrl("/opds/v1.2/series") + "?search={searchTerms}"
    call.respondText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
        <ShortName>Search</ShortName>
        <Description>Search for series</Description>
        <Url type="application/atom+xml;profile=opds-catalog;kind=acquisition" template="${template.xml()}"/>
      </OpenSearchDescription>
      """.trimIndent(),
      OPENSEARCH_CONTENT_TYPE,
    )
  }
  get("/opds/v1.2/ondeck") {
    call.respondAtomBooks(
      id = "ondeck",
      title = "On Deck",
      page =
        catalog.findBooks(
          BookCatalogQuery(onDeck = true),
          call.opdsUser().catalogAccess(),
          call.opdsPageRequest(),
        ),
    )
  }
  get("/opds/v1.2/keep-reading") {
    call.respondAtomBooks(
      id = "keepReading",
      title = "Keep Reading",
      page = catalog.keepReading(call.opdsUser(), call.opdsPageRequest()),
    )
  }
  get("/opds/v1.2/series") {
    val search = call.request.queryParameters["search"]
    val publishers = call.request.queryParameters.getAll("publisher").orEmpty().toSet()
    call.respondAtomSeries(
      id = "allSeries",
      title = search?.let { "Series search for: $it" } ?: "All series",
      page =
        catalog.findSeries(
          SeriesCatalogQuery(
            fullTextSearch = search,
            publishers = publishers,
          ),
          call.opdsUser().catalogAccess(),
          call.opdsPageRequest(listOf(CatalogSort("titleSort"))),
        ),
    )
  }
  get("/opds/v1.2/series/latest") {
    call.respondAtomSeries(
      "latestSeries",
      "Latest series",
      catalog.findSeries(
        SeriesCatalogQuery(),
        call.opdsUser().catalogAccess(),
        call.opdsPageRequest(
          listOf(CatalogSort("lastModified", CatalogSortDirection.DESC)),
        ),
      ),
    )
  }
  get("/opds/v1.2/books/latest") {
    call.respondAtomBooks(
      "latestBooks",
      "Latest books",
      catalog.findBooks(
        BookCatalogQuery(),
        call.opdsUser().catalogAccess(),
        call.opdsPageRequest(
          listOf(CatalogSort("created", CatalogSortDirection.DESC)),
        ),
      ),
    )
  }
  get("/opds/v1.2/libraries") {
    val user = call.opdsUser()
    call.respondAtom(
      "allLibraries",
      "All libraries",
      libraries.visibleTo(user).map { library ->
        AtomEntry(
          library.id.value,
          library.name,
          "Browse ${library.name}",
          "/opds/v1.2/libraries/${library.id.value}",
        )
      },
    )
  }
  get("/opds/v1.2/collections") {
    val user = call.opdsUser()
    call.respondAtom(
      "allCollections",
      "All collections",
      collections.findAll().visibleCollections(catalog, user).map { it.toAtomEntry() },
    )
  }
  get("/opds/v1.2/readlists") {
    val user = call.opdsUser()
    call.respondAtom(
      "allReadLists",
      "All read lists",
      readLists.findAll().visibleReadLists(catalog, user).map { it.toAtomEntry() },
    )
  }
  get("/opds/v1.2/publishers") {
    val user = call.opdsUser()
    val publishers =
      catalog
        .findSeries(
          SeriesCatalogQuery(),
          user.catalogAccess(),
          CatalogPageRequest(size = CatalogPageRequest.MAXIMUM_PAGE_SIZE),
        ).content
        .map(CatalogSeries::metadata)
        .map { it.publisher }
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
    call.respondAtom(
      "allPublishers",
      "All publishers",
      publishers.map { publisher ->
        AtomEntry(
          publisher,
          publisher,
          "Browse $publisher",
          "/opds/v1.2/series?publisher=${publisher.urlQuery()}",
        )
      },
    )
  }
  get("/opds/v1.2/series/{id}") {
    val id = SeriesId(requireNotNull(call.parameters["id"]))
    val series = catalog.findSeriesByIdOrNull(id, call.opdsUser().catalogAccess())
    if (series == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respondAtomBooks(
        id.value,
        series.metadata.title,
        catalog.findBooks(
          BookCatalogQuery(seriesId = id),
          call.opdsUser().catalogAccess(),
          call.opdsPageRequest(listOf(CatalogSort("numberSort"))),
        ),
      )
    }
  }
  get("/opds/v1.2/libraries/{id}") {
    val library = call.visibleLibrary(libraries) ?: return@get
    call.respondAtomSeries(
      library.id.value,
      library.name,
      catalog.findSeries(
        SeriesCatalogQuery(libraryIds = setOf(library.id)),
        call.opdsUser().catalogAccess(),
        call.opdsPageRequest(listOf(CatalogSort("titleSort"))),
      ),
    )
  }
  get("/opds/v1.2/collections/{id}") {
    val collection =
      collections.findByIdOrNull(CollectionId(requireNotNull(call.parameters["id"])))
    if (collection == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respondAtomSeriesItems(collection.name, collection.seriesIds, catalog, call.opdsUser())
    }
  }
  get("/opds/v1.2/readlists/{id}") {
    val readList = readLists.findByIdOrNull(ReadListId(requireNotNull(call.parameters["id"])))
    if (readList == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respondAtomBookItems(readList.name, readList.bookIds, catalog, call.opdsUser())
    }
  }
  get("/opds/v1.2/books/{bookId}/thumbnail/small") {
    call.respondOpdsThumbnail(catalog, artwork, content, maximumDimension = 300)
  }
  get("/opds/v1.2/books/{bookId}/thumbnail") {
    call.respondOpdsThumbnail(catalog, artwork, content, maximumDimension = 1_600)
  }
  get("/opds/v1.2/books/{bookId}/pages/{pageNumber}") {
    call.respondOpdsPage(catalog, content, zeroBasedPageNumber = true)
  }
}

private fun Route.opdsV2Routes(
  catalog: CatalogReadRepository,
  libraries: LibraryRepository,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
  facets: MetadataFacetRepository?,
) {
  listOf("/opds/v2/catalog", "/opds/v2/libraries").forEach { path ->
    get(path) {
      call.respondRecommended(catalog, libraries, collections, readLists, library = null)
    }
  }
  get("/opds/v2/libraries/{id}") {
    val library = call.visibleLibrary(libraries) ?: return@get
    call.respondRecommended(catalog, libraries, collections, readLists, library)
  }
  listOf("/opds/v2/libraries/keep-reading", "/opds/v2/libraries/{id}/keep-reading")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        val page =
          catalog.keepReading(
            call.opdsUser(),
            call.opdsPageRequest(),
            library?.id,
          )
        call.respondOpds(
          call.bookFeed(
            title = "${library?.name ?: "All libraries"} - Keep Reading",
            path = call.request.path(),
            page = page,
            modified = library.opdsModified(),
          ),
        )
      }
    }
  listOf("/opds/v2/libraries/on-deck", "/opds/v2/libraries/{id}/on-deck")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        call.respondOpds(
          call.bookFeed(
            title = "${library?.name ?: "All libraries"} - On Deck",
            path = call.request.path(),
            page =
              catalog.findBooks(
                BookCatalogQuery(
                  libraryIds = library?.let { setOf(it.id) }.orEmpty(),
                  onDeck = true,
                ),
                call.opdsUser().catalogAccess(),
                call.opdsPageRequest(),
              ),
            modified = library.opdsModified(),
          ),
        )
      }
    }
  listOf("/opds/v2/libraries/books/latest", "/opds/v2/libraries/{id}/books/latest")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        call.respondOpds(
          call.bookFeed(
            title = "${library?.name ?: "All libraries"} - Latest Books",
            path = call.request.path(),
            page =
              catalog.findBooks(
                BookCatalogQuery(libraryIds = library?.let { setOf(it.id) }.orEmpty()),
                call.opdsUser().catalogAccess(),
                call.opdsPageRequest(
                  listOf(CatalogSort("created", CatalogSortDirection.DESC)),
                ),
              ),
            modified = library.opdsModified(),
          ),
        )
      }
    }
  listOf("/opds/v2/libraries/series/latest", "/opds/v2/libraries/{id}/series/latest")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        call.respondOpds(
          call.seriesFeed(
            title = "${library?.name ?: "All libraries"} - Latest Series",
            path = call.request.path(),
            page =
              catalog.findSeries(
                SeriesCatalogQuery(
                  libraryIds = library?.let { setOf(it.id) }.orEmpty(),
                  oneshot = false,
                ),
                call.opdsUser().catalogAccess(),
                call.opdsPageRequest(
                  listOf(CatalogSort("lastModified", CatalogSortDirection.DESC)),
                ),
              ),
            modified = library.opdsModified(),
          ),
        )
      }
    }
  listOf("/opds/v2/libraries/browse", "/opds/v2/libraries/{id}/browse")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        val publishers = call.request.queryParameters.getAll("publisher").orEmpty()
        val publisherCondition =
          publishers
            .map {
              CatalogSearchCondition.Predicate(
                CatalogSearchField.PUBLISHER,
                CatalogSearchOperator.IS,
                it,
              )
            }.takeIf { it.isNotEmpty() }
            ?.let(CatalogSearchCondition::AllOf)
        val page =
          catalog.findSeries(
            SeriesCatalogQuery(
              libraryIds = library?.let { setOf(it.id) }.orEmpty(),
              condition = publisherCondition,
            ),
            call.opdsUser().catalogAccess(),
            call.opdsPageRequest(listOf(CatalogSort("titleSort"))),
          )
        val user = call.opdsUser()
        val publisherLinks =
          facets
            ?.findValues(
              MetadataFacet.PUBLISHER,
              MetadataFacetQuery(
                libraryIds = library?.let { setOf(it.id) }.orEmpty(),
              ),
              user.catalogAccess(),
            ).orEmpty()
            .map { publisher ->
              WPLinkDto(
                title = publisher,
                href =
                  call.opdsUrl(call.request.path()) +
                    "?publisher=${publisher.urlQuery()}",
                type = OPDS_V2_MEDIA_TYPE,
              )
            }
        call.respondOpds(
          OpdsFeedDto(
            metadata =
              page.toOpdsMetadata(
                title = library?.name ?: "All libraries",
                modified = library?.updatedAtMillis?.let { Instant.ofEpochMilli(it).toString() }
                  ?: Instant.now().toString(),
              ),
            links = call.standardV2Links(call.request.path(), page),
            navigation =
              call.libraryNavigation(
                catalog,
                collections,
                readLists,
                library,
                user,
              ),
            groups =
              buildList {
                add(
                  OpdsFeedGroupDto(
                    metadata = OpdsFeedMetadataDto("Series"),
                    navigation = page.content.map { it.toV2Link(call) },
                  ),
                )
                if (publisherLinks.isNotEmpty()) {
                  add(
                    OpdsFeedGroupDto(
                      metadata = OpdsFeedMetadataDto("Publisher"),
                      navigation = publisherLinks,
                    ),
                  )
                }
              },
          ),
        )
      }
    }
  listOf("/opds/v2/libraries/collections", "/opds/v2/libraries/{id}/collections")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        val user = call.opdsUser()
        val visible =
          collections.findAll().visibleCollections(catalog, user).filter { item ->
            library == null ||
              item.seriesIds.any { id ->
                catalog.findSeriesByIdOrNull(id, user.catalogAccess())?.series?.libraryId == library.id
              }
          }
        val page =
          visible
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SeriesCollection::name))
            .toPage(call.opdsPageRequest())
        call.respondOpds(
          OpdsFeedDto(
            metadata =
              page.toOpdsMetadata(
                title = "${library?.name ?: "All libraries"} - Collections",
                modified = library.opdsModified(),
              ),
            links = call.standardV2Links(call.request.path(), page),
            navigation =
              call.libraryNavigation(
                catalog,
                collections,
                readLists,
                library,
                user,
              ),
            groups =
              listOf(
                OpdsFeedGroupDto(
                  metadata = OpdsFeedMetadataDto("Collections"),
                  navigation = page.content.map { it.toV2Link(call) },
                ),
              ),
          ),
        )
      }
    }
  get("/opds/v2/collections/{id}") {
    val user = call.opdsUser()
    val item =
      collections
        .findAll()
        .visibleCollections(catalog, user)
        .firstOrNull { it.id.value == call.parameters["id"] }
    if (item == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      val visible =
        item.seriesIds.mapNotNull {
          catalog.findSeriesByIdOrNull(it, user.catalogAccess())
        }
      val ordered =
        if (item.ordered) {
          visible
        } else {
          visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.titleSort })
        }
      val page = ordered.toPage(call.opdsPageRequest())
      call.respondOpds(
        OpdsFeedDto(
          metadata =
            page.toOpdsMetadata(
              title = item.name,
              modified = Instant.ofEpochMilli(item.updatedAtMillis).toString(),
            ),
          links = call.standardV2Links(call.request.path(), page),
          navigation = page.content.map { it.toV2Link(call) },
        ),
      )
    }
  }
  listOf("/opds/v2/libraries/readlists", "/opds/v2/libraries/{id}/readlists")
    .forEach { path ->
      get(path) {
        val library =
          if (call.parameters["id"] == null) null
          else call.visibleLibrary(libraries) ?: return@get
        val user = call.opdsUser()
        val visible =
          readLists.findAll().visibleReadLists(catalog, user).filter { item ->
            library == null ||
              item.bookIds.any { id ->
                catalog.findBookByIdOrNull(id, user.catalogAccess())?.book?.libraryId == library.id
              }
          }
        val page =
          visible
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ReadList::name))
            .toPage(call.opdsPageRequest())
        call.respondOpds(
          OpdsFeedDto(
            metadata =
              page.toOpdsMetadata(
                title = "${library?.name ?: "All libraries"} - Read Lists",
                modified = library.opdsModified(),
              ),
            links = call.standardV2Links(call.request.path(), page),
            navigation =
              call.libraryNavigation(
                catalog,
                collections,
                readLists,
                library,
                user,
              ),
            groups =
              listOf(
                OpdsFeedGroupDto(
                  metadata = OpdsFeedMetadataDto("Read Lists"),
                  navigation = page.content.map { it.toV2Link(call) },
                ),
              ),
          ),
        )
      }
    }
  get("/opds/v2/readlists/{id}") {
    val user = call.opdsUser()
    val item =
      readLists
        .findAll()
        .visibleReadLists(catalog, user)
        .firstOrNull { it.id.value == call.parameters["id"] }
    if (item == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      val visible =
        item.bookIds.mapNotNull {
          catalog.findBookByIdOrNull(it, user.catalogAccess())
        }
      val ordered =
        if (item.ordered) {
          visible
        } else {
          visible.sortedWith(
            compareBy<CatalogBook> { it.metadata.releaseDate }
              .thenBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.title },
          )
        }
      call.respondOpds(
        call.bookFeed(
          title = item.name,
          path = call.request.path(),
          page = ordered.toPage(call.opdsPageRequest()),
          modified = Instant.ofEpochMilli(item.updatedAtMillis).toString(),
        ),
      )
    }
  }
  get("/opds/v2/series/{id}") {
    val id = SeriesId(requireNotNull(call.parameters["id"]))
    val user = call.opdsUser()
    val item = catalog.findSeriesByIdOrNull(id, user.catalogAccess())
    if (item == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      val selectedTag = call.request.queryParameters["tag"]
      val tagCondition =
        selectedTag?.let {
          CatalogSearchCondition.Predicate(
            CatalogSearchField.TAG,
            CatalogSearchOperator.IS,
            it,
          )
        }
      val tagLinks =
        facets
          ?.findValues(
            MetadataFacet.BOOK_TAG,
            MetadataFacetQuery(seriesId = id),
            user.catalogAccess(),
          ).orEmpty()
          .map { tag ->
            WPLinkDto(
              title = tag,
              rel = if (tag == selectedTag) "self" else null,
              href = call.opdsUrl(call.request.path()) + "?tag=${tag.urlQuery()}",
              type = OPDS_V2_MEDIA_TYPE,
            )
          }
      call.respondOpds(
        call.bookFeed(
          title = item.metadata.title,
          path = call.request.path(),
          page =
            catalog.findBooks(
              BookCatalogQuery(
                seriesId = id,
                condition = tagCondition,
              ),
              user.catalogAccess(),
              call.opdsPageRequest(listOf(CatalogSort("numberSort"))),
            ),
          modified = Instant.ofEpochMilli(item.series.updatedAtMillis).toString(),
          description = item.metadata.summary.ifBlank { item.booksMetadata.summary },
          facets =
            tagLinks
              .takeIf(List<WPLinkDto>::isNotEmpty)
              ?.let {
                listOf(
                  OpdsFacetDto(
                    metadata = OpdsFeedMetadataDto("Tag"),
                    links = it,
                  ),
                )
              }.orEmpty(),
        ),
      )
    }
  }
  get("/opds/v2/search") {
    val query = call.request.queryParameters["query"].orEmpty()
    val user = call.opdsUser()
    val titleCondition = query.toOpdsTitleCondition()
    val searchPage =
      CatalogPageRequest(
        size = 20,
        sorts = listOf(CatalogSort("titleSort")),
      )
    val series =
      catalog.findSeries(
        SeriesCatalogQuery(
          oneshot = false,
          condition = titleCondition,
        ),
        user.catalogAccess(),
        searchPage,
      )
    val books =
      catalog.findBooks(
        BookCatalogQuery(condition = titleCondition),
        user.catalogAccess(),
        searchPage.copy(sorts = listOf(CatalogSort("title"))),
      )
    val normalizedQuery = query.trim()
    val visibleCollections =
      collections
        .findAll()
        .visibleCollections(catalog, user)
        .filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        .take(20)
    val visibleReadLists =
      readLists
        .findAll()
        .visibleReadLists(catalog, user)
        .filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        .take(20)
    call.respondOpds(
      OpdsFeedDto(
        metadata =
          OpdsFeedMetadataDto(
            title = "Search results",
            modified = Instant.now().toString(),
          ),
        links = call.standardV2Links(call.request.path(), includeSelf = false),
        groups =
          listOfNotNull(
            OpdsFeedGroupDto(
              metadata = OpdsFeedMetadataDto("Series"),
              navigation = series.content.map { it.toV2Link(call) },
            ).takeUnless { it.navigation.isEmpty() },
            OpdsFeedGroupDto(
              metadata = OpdsFeedMetadataDto("Books"),
              publications = books.content.mapNotNull { it.toOpdsPublication(call) },
            ).takeUnless { it.publications.isEmpty() },
            OpdsFeedGroupDto(
              metadata = OpdsFeedMetadataDto("Collections"),
              navigation = visibleCollections.map { it.toV2Link(call) },
            ).takeUnless { it.navigation.isEmpty() },
            OpdsFeedGroupDto(
              metadata = OpdsFeedMetadataDto("Read Lists"),
              navigation = visibleReadLists.map { it.toV2Link(call) },
            ).takeUnless { it.navigation.isEmpty() },
          ),
      ),
    )
  }
  get("/opds/v2/books/{bookId}/pages/{pageNumber}") {
    call.respondOpdsPage(catalog, content, zeroBasedPageNumber = false)
  }
  get("/opds/v2/books/{bookId}/thumbnail") {
    call.respondOpdsThumbnail(catalog, artwork, content, maximumDimension = 1_600)
  }
  get("/opds/v2/books/{bookId}/file") {
    call.streamBook(catalog, content)
  }
}

private fun Route.opdsV2ManifestRoutes(
  catalog: CatalogReadRepository,
  progress: ReadProgressLifecycle,
) {
  get("/opds/v2/books/{bookId}/manifest") {
    call.respondManifest(catalog)
  }
  get("/opds/v2/books/{bookId}/manifest/epub") {
    call.respondProfileManifest(catalog, MediaProfile.EPUB)
  }
  get("/opds/v2/books/{bookId}/manifest/pdf") {
    call.respondProfileManifest(catalog, MediaProfile.PDF)
  }
  get("/opds/v2/books/{bookId}/manifest/divina") {
    call.respondProfileManifest(catalog, MediaProfile.DIVINA)
  }
  get("/opds/v2/books/{bookId}/progression") {
    call.respondProgression(catalog, progress)
  }
  put("/opds/v2/books/{bookId}/progression") {
    call.updateProgression(catalog, progress)
  }
}

@Serializable
data class OpdsFeedDto(
  val metadata: OpdsFeedMetadataDto,
  val links: List<WPLinkDto> = emptyList(),
  val navigation: List<WPLinkDto> = emptyList(),
  val facets: List<OpdsFacetDto> = emptyList(),
  val groups: List<OpdsFeedGroupDto> = emptyList(),
  val publications: List<WPPublicationDto> = emptyList(),
)

@Serializable
data class OpdsFeedGroupDto(
  val metadata: OpdsFeedMetadataDto,
  val links: List<WPLinkDto> = emptyList(),
  val navigation: List<WPLinkDto> = emptyList(),
  val publications: List<WPPublicationDto> = emptyList(),
)

@Serializable
data class OpdsFacetDto(
  val metadata: OpdsFeedMetadataDto,
  val links: List<WPLinkDto> = emptyList(),
)

@Serializable
data class OpdsFeedMetadataDto(
  val title: String,
  val subTitle: String? = null,
  @SerialName("@type")
  val type: String? = null,
  val identifier: String? = null,
  val modified: String? = null,
  val description: String? = null,
  val itemsPerPage: Int? = null,
  val currentPage: Int? = null,
  val numberOfItems: Long? = null,
)

@Serializable
data class OpdsAuthenticationDocumentDto(
  val authentication: List<OpdsAuthenticationFlowDto>,
  val title: String,
  val id: String,
  val description: String? = null,
  val links: List<WPLinkDto> = emptyList(),
)

@Serializable
data class OpdsAuthenticationFlowDto(
  val type: String = "http://opds-spec.org/auth/basic",
  val labels: OpdsAuthenticationLabelsDto? = null,
  val links: List<WPLinkDto> = emptyList(),
)

@Serializable
data class OpdsAuthenticationLabelsDto(
  val login: String? = null,
  val password: String? = null,
)

private data class AtomEntry(
  val id: String,
  val title: String,
  val content: String,
  val href: String,
  val acquisition: Boolean = false,
  val thumbnailHref: String? = null,
  val authors: List<String> = emptyList(),
)

private suspend fun ApplicationCall.respondRecommended(
  catalog: CatalogReadRepository,
  libraries: LibraryRepository,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  library: Library?,
) {
  val user = opdsUser()
  val libraryIds = library?.let { setOf(it.id) }.orEmpty()
  val recommendedSize = CatalogPageRequest(size = 5)
  val keepReading = catalog.keepReading(user, recommendedSize, library?.id)
  val onDeck =
    catalog.findBooks(
      BookCatalogQuery(libraryIds = libraryIds, onDeck = true),
      user.catalogAccess(),
      recommendedSize,
    )
  val latest =
    catalog.findBooks(
      BookCatalogQuery(libraryIds = libraryIds),
      user.catalogAccess(),
      CatalogPageRequest(
        size = 5,
        sorts = listOf(CatalogSort("created", CatalogSortDirection.DESC)),
      ),
    )
  val latestSeries =
    catalog.findSeries(
      SeriesCatalogQuery(libraryIds = libraryIds, oneshot = false),
      user.catalogAccess(),
      CatalogPageRequest(
        size = 5,
        sorts = listOf(CatalogSort("lastModified", CatalogSortDirection.DESC)),
      ),
    )
  val libraryBase =
    library?.let { "/opds/v2/libraries/${it.id.value}" } ?: "/opds/v2/libraries"
  respondOpds(
    OpdsFeedDto(
      metadata =
        OpdsFeedMetadataDto(
          title = "${library?.name ?: "All libraries"} - Recommended",
          modified =
            library?.updatedAtMillis?.let { Instant.ofEpochMilli(it).toString() }
              ?: Instant.now().toString(),
        ),
      links = standardV2Links(request.path()),
      navigation = libraryNavigation(catalog, collections, readLists, library, user),
      groups =
        buildList {
          if (library == null) {
            add(
              OpdsFeedGroupDto(
                OpdsFeedMetadataDto("Libraries"),
                links = listOf(selfV2Link("/opds/v2/libraries")),
                navigation = libraries.visibleTo(user).map { it.toV2Link(this@respondRecommended) },
              ),
            )
          }
          addBookGroup(
            "Keep Reading",
            "$libraryBase/keep-reading",
            keepReading,
            this@respondRecommended,
          )
          addBookGroup(
            "On Deck",
            "$libraryBase/on-deck",
            onDeck,
            this@respondRecommended,
          )
          addBookGroup(
            "Latest Books",
            "$libraryBase/books/latest",
            latest,
            this@respondRecommended,
          )
          if (latestSeries.content.isNotEmpty()) {
            add(
              OpdsFeedGroupDto(
                latestSeries.toOpdsMetadata("Latest Series"),
                links =
                  listOf(
                    subsectionSelfV2Link(
                      "Latest Series",
                      "$libraryBase/series/latest",
                    ),
                  ),
                navigation = latestSeries.content.map { it.toV2Link(this@respondRecommended) },
              ),
            )
          }
        },
    ),
  )
}

private fun ApplicationCall.libraryNavigation(
  catalog: CatalogReadRepository,
  collections: SeriesCollectionRepository,
  readLists: ReadListRepository,
  library: Library?,
  user: User,
): List<WPLinkDto> {
  val libraryBase =
    library?.let { "/opds/v2/libraries/${it.id.value}" }
      ?: "/opds/v2/libraries"
  val hasCollections =
    collections
      .findAll()
      .visibleCollections(catalog, user)
      .any { item ->
        library == null ||
          item.seriesIds.any { id ->
            catalog.findSeriesByIdOrNull(id, user.catalogAccess())?.series?.libraryId == library.id
          }
      }
  val hasReadLists =
    readLists
      .findAll()
      .visibleReadLists(catalog, user)
      .any { item ->
        library == null ||
          item.bookIds.any { id ->
            catalog.findBookByIdOrNull(id, user.catalogAccess())?.book?.libraryId == library.id
          }
      }
  return buildList {
    add(
      WPLinkDto(
        title = "Recommended",
        rel = OPDS_SUBSECTION_REL,
        href = opdsUrl(libraryBase),
        type = OPDS_V2_MEDIA_TYPE,
      ),
    )
    add(
      WPLinkDto(
        title = "Browse",
        rel = OPDS_SUBSECTION_REL,
        href = opdsUrl("$libraryBase/browse"),
        type = OPDS_V2_MEDIA_TYPE,
      ),
    )
    if (hasCollections) {
      add(
        WPLinkDto(
          title = "Collections",
          rel = OPDS_SUBSECTION_REL,
          href = opdsUrl("$libraryBase/collections"),
          type = OPDS_V2_MEDIA_TYPE,
        ),
      )
    }
    if (hasReadLists) {
      add(
        WPLinkDto(
          title = "Read lists",
          rel = OPDS_SUBSECTION_REL,
          href = opdsUrl("$libraryBase/readlists"),
          type = OPDS_V2_MEDIA_TYPE,
        ),
      )
    }
  }
}

private fun MutableList<OpdsFeedGroupDto>.addBookGroup(
  title: String,
  path: String,
  page: CatalogPage<CatalogBook>,
  call: ApplicationCall,
) {
  val publications = page.content.mapNotNull { it.toOpdsPublication(call) }
  if (publications.isNotEmpty()) {
    add(
      OpdsFeedGroupDto(
        metadata = page.toOpdsMetadata(title),
        links = listOf(call.subsectionSelfV2Link(title, path)),
        publications = publications,
      ),
    )
  }
}

private fun ApplicationCall.bookFeed(
  title: String,
  path: String,
  page: CatalogPage<CatalogBook>,
  modified: String = Instant.now().toString(),
  description: String? = null,
  facets: List<OpdsFacetDto> = emptyList(),
): OpdsFeedDto =
  OpdsFeedDto(
    metadata =
      OpdsFeedMetadataDto(
        title = title,
        modified = modified,
        description = description?.takeIf(String::isNotBlank),
        itemsPerPage = page.size,
        currentPage = page.page + 1,
        numberOfItems = page.totalElements,
      ),
    links = standardV2Links(path, page),
    facets = facets,
    publications = page.content.mapNotNull { it.toOpdsPublication(this) },
  )

private fun ApplicationCall.seriesFeed(
  title: String,
  path: String,
  page: CatalogPage<CatalogSeries>,
  modified: String = Instant.now().toString(),
): OpdsFeedDto =
  OpdsFeedDto(
    metadata =
      OpdsFeedMetadataDto(
        title = title,
        modified = modified,
        itemsPerPage = page.size,
        currentPage = page.page + 1,
        numberOfItems = page.totalElements,
      ),
    links = standardV2Links(path, page),
    navigation = page.content.map { it.toV2Link(this) },
  )

private fun Library?.opdsModified(): String =
  this?.updatedAtMillis?.let { Instant.ofEpochMilli(it).toString() }
    ?: Instant.now().toString()

private fun ApplicationCall.standardV2Links(
  path: String,
  page: CatalogPage<*>? = null,
  includeSelf: Boolean = true,
): List<WPLinkDto> =
  buildList {
    if (includeSelf) {
      add(selfV2Link(path))
    }
    add(
      WPLinkDto(
        title = "Home",
        rel = "start",
        href = opdsUrl("/opds/v2/catalog"),
        type = OPDS_V2_MEDIA_TYPE,
      ),
    )
    add(
      WPLinkDto(
        title = "Search",
        rel = "search",
        href = opdsUrl("/opds/v2/search") + "{?query}",
        type = OPDS_V2_MEDIA_TYPE,
        templated = true,
      ),
    )
    if (page != null && page.page > 0) {
      add(WPLinkDto(rel = "previous", href = opdsUrl(path) + "?page=${page.page - 1}"))
    }
    if (page != null && (page.page + 1L) * page.size < page.totalElements) {
      add(WPLinkDto(rel = "next", href = opdsUrl(path) + "?page=${page.page + 1}"))
    }
  }

private fun ApplicationCall.selfV2Link(path: String): WPLinkDto =
  WPLinkDto(
    rel = "self",
    href = opdsUrl(path),
  )

private fun ApplicationCall.subsectionSelfV2Link(
  title: String,
  path: String,
): WPLinkDto =
  WPLinkDto(
    title = title,
    rel = "self",
    href = opdsUrl(path),
    type = OPDS_V2_MEDIA_TYPE,
  )

private fun CatalogPage<*>.toOpdsMetadata(
  title: String,
  modified: String? = null,
): OpdsFeedMetadataDto =
  OpdsFeedMetadataDto(
    title = title,
    modified = modified,
    itemsPerPage = size,
    currentPage = page + 1,
    numberOfItems = totalElements,
  )

private fun String.toOpdsTitleCondition(): CatalogSearchCondition? {
  val terms =
    trim()
      .split(Regex("\\s+"))
      .filter(String::isNotEmpty)
  return terms
    .map { term ->
      CatalogSearchCondition.Predicate(
        field = CatalogSearchField.TITLE,
        operator = CatalogSearchOperator.CONTAINS,
        value = term,
      )
    }.takeIf { it.isNotEmpty() }
    ?.let(CatalogSearchCondition::AllOf)
}

private fun CatalogBook.toOpdsPublication(call: ApplicationCall): WPPublicationDto? {
  val apiBase = call.opdsUrl("/opds/v2")
  val manifest = toWebPubManifest(apiBase) ?: return null
  val authentication =
    mapOf(
      "authenticate" to
        mapOf(
          "href" to call.opdsUrl("/opds/v2/auth"),
          "type" to OPDS_AUTH_MEDIA_TYPE,
        ),
    )
  return manifest.copy(
    metadata =
      manifest.metadata.copy(
        conformsTo = null,
        language = null,
        publisher = emptyList(),
        readingProgression = null,
        belongsTo =
          manifest.metadata.belongsTo?.copy(
            series =
              manifest.metadata.belongsTo.series.map { series ->
                series.copy(
                  links =
                    listOf(
                      WPLinkDto(
                        href = "$apiBase/series/${book.seriesId.value}",
                        type = OPDS_V2_MEDIA_TYPE,
                      ),
                    ),
                )
              },
          ),
        rendition = emptyMap(),
      ),
    links =
      manifest.links.map { link ->
        link.copy(
          properties = authentication,
        )
      } +
        WPLinkDto(
          rel = "http://www.cantook.com/api/progression",
          href = "$apiBase/books/${book.id.value}/progression",
          type = "application/vnd.readium.progression+json",
          properties = authentication,
        ),
    images =
      listOf(
        WPLinkDto(
          href = "$apiBase/books/${book.id.value}/thumbnail",
          type = "image/jpeg",
          properties = authentication,
        ),
      ),
    readingOrder = emptyList(),
    resources = emptyList(),
    toc = emptyList(),
    landmarks = emptyList(),
    pageList = emptyList(),
  )
}

private fun CatalogSeries.toV2Link(call: ApplicationCall): WPLinkDto =
  WPLinkDto(
    title = metadata.title,
    href = call.opdsUrl("/opds/v2/series/${series.id.value}"),
    type = OPDS_V2_MEDIA_TYPE,
  )

private fun Library.toV2Link(call: ApplicationCall): WPLinkDto =
  WPLinkDto(
    title = name,
    href = call.opdsUrl("/opds/v2/libraries/${id.value}"),
    type = OPDS_V2_MEDIA_TYPE,
  )

private fun SeriesCollection.toV2Link(call: ApplicationCall): WPLinkDto =
  WPLinkDto(
    title = name,
    href = call.opdsUrl("/opds/v2/collections/${id.value}"),
    type = OPDS_V2_MEDIA_TYPE,
  )

private fun ReadList.toV2Link(call: ApplicationCall): WPLinkDto =
  WPLinkDto(
    title = name,
    href = call.opdsUrl("/opds/v2/readlists/${id.value}"),
    type = OPDS_V2_MEDIA_TYPE,
  )

private suspend fun ApplicationCall.respondAtom(
  id: String,
  title: String,
  entries: List<AtomEntry>,
) {
  val links =
    """
    <link rel="self" href="${opdsUrl(request.path()).xml()}" type="$ATOM_NAVIGATION_MEDIA_TYPE"/>
    <link rel="start" href="${opdsUrl("/opds/v1.2/catalog").xml()}" type="$ATOM_NAVIGATION_MEDIA_TYPE"/>
    """.trimIndent()
  val body =
    entries.joinToString(separator = "\n") { entry ->
      val href =
        if (entry.href.startsWith("http://") || entry.href.startsWith("https://")) {
          entry.href
        } else {
          opdsUrl(entry.href)
        }
      buildString {
        append("<entry><title>${entry.title.xml()}</title>")
        append("<id>${entry.id.xml()}</id><updated>${Instant.now()}</updated>")
        append("<content type=\"text\">${entry.content.xml()}</content>")
        entry.authors.forEach { append("<author><name>${it.xml()}</name></author>") }
        append(
          "<link rel=\"${if (entry.acquisition) OPDS_ACQUISITION_REL else OPDS_SUBSECTION_REL}\" " +
            "href=\"${href.xml()}\" type=\"" +
            "${if (entry.acquisition) "application/octet-stream" else ATOM_NAVIGATION_MEDIA_TYPE}\"/>",
        )
        entry.thumbnailHref?.let {
          append(
            "<link rel=\"http://opds-spec.org/image/thumbnail\" " +
              "href=\"${opdsUrl(it).xml()}\" type=\"image/jpeg\"/>",
          )
        }
        append("</entry>")
      }
    }
  respondText(
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <id>${id.xml()}</id>
      <title>${title.xml()}</title>
      <updated>${Instant.now()}</updated>
      <author><name>Komga</name><uri>https://komga.org</uri></author>
      $links
      $body
    </feed>
    """.trimIndent(),
    ATOM_CONTENT_TYPE,
  )
}

private suspend fun ApplicationCall.respondAtomBooks(
  id: String,
  title: String,
  page: CatalogPage<CatalogBook>,
) {
  respondAtom(
    id,
    title,
    page.content.map { item ->
      AtomEntry(
        id = item.book.id.value,
        title = "${item.seriesTitle} - ${item.metadata.title}",
        content = item.metadata.summary,
        href = "/api/v1/books/${item.book.id.value}/file",
        acquisition = true,
        thumbnailHref = "/opds/v1.2/books/${item.book.id.value}/thumbnail/small",
        authors = item.metadata.authors.map { it.name },
      )
    },
  )
}

private suspend fun ApplicationCall.respondAtomSeries(
  id: String,
  title: String,
  page: CatalogPage<CatalogSeries>,
) {
  respondAtom(
    id,
    title,
    page.content.map { item ->
      AtomEntry(
        id = item.series.id.value,
        title = item.metadata.title,
        content = item.metadata.summary,
        href = "/opds/v1.2/series/${item.series.id.value}",
      )
    },
  )
}

private suspend fun ApplicationCall.respondAtomSeriesItems(
  title: String,
  ids: List<SeriesId>,
  catalog: CatalogReadRepository,
  user: User,
) {
  val visible = ids.mapNotNull { catalog.findSeriesByIdOrNull(it, user.catalogAccess()) }
  respondAtomSeries(title, title, visible.toPage(opdsPageRequest()))
}

private suspend fun ApplicationCall.respondAtomBookItems(
  title: String,
  ids: List<BookId>,
  catalog: CatalogReadRepository,
  user: User,
) {
  val visible = ids.mapNotNull { catalog.findBookByIdOrNull(it, user.catalogAccess()) }
  respondAtomBooks(title, title, visible.toPage(opdsPageRequest()))
}

private suspend fun ApplicationCall.respondOpdsThumbnail(
  catalog: CatalogReadRepository,
  artwork: ArtworkLifecycle,
  content: BookContentAccess,
  maximumDimension: Int,
) {
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  if (catalog.findBookByIdOrNull(bookId, opdsUser().catalogAccess()) == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val selected =
    artwork.selectedContentOrNull(
      ArtworkOwner(ArtworkOwnerKind.MEDIA_ITEM, bookId.value),
    )
  if (selected != null) {
    val body = selected.bytes.komgaCachedBody()
    if (respondNotModified(body, lastModifiedMillis = null)) return
    respondBytes(body.bytes, ContentType.parse(selected.artwork.mediaType))
    return
  }
  val opened =
    runCatching {
      content.openPage(
        bookId,
        1,
        PageImageRequest(PageImageFormat.JPEG, maximumDimension),
      )
    }.getOrNull()
  if (opened == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  try {
    val body = opened.readKomgaCachedBody()
    if (respondNotModified(body, lastModifiedMillis = null)) return
    respondBytes(body.bytes, ContentType.Image.JPEG)
  } finally {
    opened.close()
  }
}

private suspend fun ApplicationCall.respondOpdsPage(
  catalog: CatalogReadRepository,
  content: BookContentAccess,
  zeroBasedPageNumber: Boolean,
) {
  val user = opdsUser()
  if (UserRole.PAGE_STREAMING !in user.roles) {
    respond(HttpStatusCode.Forbidden)
    return
  }
  val bookId = BookId(requireNotNull(parameters["bookId"]))
  val item = catalog.findBookByIdOrNull(bookId, user.catalogAccess())
  if (item == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  val requestedPage = parameters["pageNumber"]?.toIntOrNull()
  if (requestedPage == null) {
    respond(HttpStatusCode.BadRequest)
    return
  }
  val page = if (zeroBasedPageNumber) requestedPage + 1 else requestedPage
  val lastModified = item.media?.updatedAtMillis ?: item.book.updatedAtMillis
  if (respondNotModifiedByTimestamp(lastModified)) return
  val format =
    when (request.queryParameters["convert"]?.lowercase()) {
      null -> null
      "jpeg" -> PageImageFormat.JPEG
      "png" -> PageImageFormat.PNG
      else -> {
        respond(HttpStatusCode.BadRequest)
        return
      }
    }
  val opened =
    runCatching { content.openPage(bookId, page, PageImageRequest(format = format)) }.getOrNull()
  if (opened == null) {
    respond(HttpStatusCode.NotFound)
    return
  }
  try {
    val body = opened.readKomgaCachedBody()
    if (respondNotModified(body, lastModified)) return
    response.header(
      HttpHeaders.ContentDisposition,
      komgaContentDisposition(
        disposition = "inline",
        fileName = "${item.book.name}-$page${opened.mediaType.komgaFileExtension(opened.fileName)}",
      ),
    )
    respondBytes(
      body.bytes,
      runCatching { ContentType.parse(opened.mediaType) }
        .getOrDefault(ContentType.Application.OctetStream),
    )
  } finally {
    opened.close()
  }
}

private fun CatalogReadRepository.keepReading(
  user: User,
  request: CatalogPageRequest,
  libraryId: LibraryId? = null,
): CatalogPage<CatalogBook> {
  return findBooks(
    BookCatalogQuery(
      libraryIds = libraryId?.let(::setOf).orEmpty(),
      keepReading = true,
    ),
    user.catalogAccess(),
    request,
  )
}

private fun <T> List<T>.toPage(request: CatalogPageRequest): CatalogPage<T> {
  val from =
    (request.page.toLong() * request.size)
      .coerceAtMost(size.toLong())
      .toInt()
  val to = (from + request.size).coerceAtMost(size)
  return CatalogPage(
    content = subList(from, to),
    page = request.page,
    size = request.size,
    totalElements = size.toLong(),
  )
}

private fun ApplicationCall.opdsPageRequest(
  defaultSorts: List<CatalogSort> = emptyList(),
): CatalogPageRequest =
  CatalogPageRequest(
    page = request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    size =
      request.queryParameters["size"]
        ?.toIntOrNull()
        ?.coerceIn(1, CatalogPageRequest.MAXIMUM_PAGE_SIZE)
        ?: 20,
    sorts = defaultSorts,
  )

private fun LibraryRepository.visibleTo(user: User): List<Library> =
  if (user.canAccessAllLibraries()) findAll() else findAllByIds(user.sharedLibraryIds)

private fun List<SeriesCollection>.visibleCollections(
  catalog: CatalogReadRepository,
  user: User,
): List<SeriesCollection> =
  filter { item ->
    item.seriesIds.any { catalog.findSeriesByIdOrNull(it, user.catalogAccess()) != null } ||
      (item.seriesIds.isEmpty() && user.isAdmin)
  }

private fun List<ReadList>.visibleReadLists(
  catalog: CatalogReadRepository,
  user: User,
): List<ReadList> =
  filter { item ->
    item.bookIds.any { catalog.findBookByIdOrNull(it, user.catalogAccess()) != null } ||
      (item.bookIds.isEmpty() && user.isAdmin)
  }

private fun SeriesCollection.toAtomEntry(): AtomEntry =
  AtomEntry(
    id = id.value,
    title = name,
    content = "Browse $name",
    href = "/opds/v1.2/collections/${id.value}",
  )

private fun ReadList.toAtomEntry(): AtomEntry =
  AtomEntry(
    id = id.value,
    title = name,
    content = summary,
    href = "/opds/v1.2/readlists/${id.value}",
  )

private suspend fun ApplicationCall.visibleLibrary(
  libraries: LibraryRepository,
): Library? {
  val rawId = parameters["id"] ?: return null
  val library = libraries.findByIdOrNull(LibraryId(rawId))
  if (library == null || !opdsUser().canAccessLibrary(library.id)) {
    respond(HttpStatusCode.NotFound)
    return null
  }
  return library
}

private fun ApplicationCall.opdsUser(): User =
  requireNotNull(principal<KomgaPrincipal>()).user

private fun ApplicationCall.opdsUrl(path: String): String {
  val origin = request.origin
  val port =
    if (
      (origin.scheme == "http" && origin.serverPort == 80) ||
      (origin.scheme == "https" && origin.serverPort == 443)
    ) {
      ""
    } else {
      ":${origin.serverPort}"
    }
  val context = request.path().substringBefore("/opds/", "")
  val normalized = if (path.startsWith("/")) path else "/$path"
  return "${origin.scheme}://${origin.serverHost}$port$context$normalized"
}

private fun String.xml(): String =
  replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun String.urlQuery(): String =
  java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

private suspend inline fun <reified T> ApplicationCall.respondOpds(
  value: T,
  contentType: ContentType = OPDS_V2_CONTENT_TYPE,
) {
  respondText(OPDS_JSON.encodeToString(value), contentType)
}

private val OPDS_JSON =
  Json {
    explicitNulls = false
    encodeDefaults = false
  }
private val ATOM_CONTENT_TYPE =
  ContentType.parse("application/atom+xml;profile=opds-catalog;kind=navigation")
private val OPENSEARCH_CONTENT_TYPE =
  ContentType.parse("application/opensearchdescription+xml")
private val OPDS_V2_CONTENT_TYPE = ContentType.parse(OPDS_V2_MEDIA_TYPE)
private val OPDS_AUTH_CONTENT_TYPE =
  ContentType.parse(OPDS_AUTH_MEDIA_TYPE)
private const val ATOM_NAVIGATION_MEDIA_TYPE =
  "application/atom+xml;profile=opds-catalog;kind=navigation"
private const val OPDS_V2_MEDIA_TYPE = "application/opds+json"
private const val OPDS_AUTH_MEDIA_TYPE = "application/opds-authentication+json"
private const val OPDS_SUBSECTION_REL = "subsection"
private const val OPDS_ACQUISITION_REL = "http://opds-spec.org/acquisition"
