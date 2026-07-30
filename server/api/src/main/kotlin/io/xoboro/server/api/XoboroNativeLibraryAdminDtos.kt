package io.xoboro.server.api

import io.xoboro.core.domain.LibrarySettings
import io.xoboro.core.domain.ScanInterval
import io.xoboro.core.domain.SeriesCover
import io.xoboro.core.domain.SourceLocation
import kotlinx.serialization.Serializable

@Serializable
data class XoboroLibraryAdministrationRequest(
  val name: String,
  val source: XoboroLibrarySourceRequest,
  val settings: XoboroLibrarySettingsRequest = XoboroLibrarySettingsRequest(),
)

@Serializable
data class XoboroLibrarySourceRequest(
  val provider: String,
  val location: String,
)

@Serializable
data class XoboroLibrarySettingsRequest(
  val importComicInfoBook: Boolean = true,
  val importComicInfoSeries: Boolean = true,
  val importComicInfoCollection: Boolean = true,
  val importComicInfoReadList: Boolean = true,
  val importComicInfoSeriesAppendVolume: Boolean = true,
  val importEpubBook: Boolean = true,
  val importPdfBook: Boolean = true,
  val importEpubSeries: Boolean = true,
  val importMylarSeries: Boolean = true,
  val importLocalArtwork: Boolean = true,
  val importBarcodeIsbn: Boolean = true,
  val scanForceModifiedTime: Boolean = false,
  val scanOnStartup: Boolean = false,
  val scanInterval: String = ScanInterval.EVERY_6H.name,
  val scanCbx: Boolean = true,
  val scanPdf: Boolean = true,
  val scanEpub: Boolean = true,
  val scanDirectoryExclusions: Set<String> = emptySet(),
  val repairExtensions: Boolean = false,
  val convertToCbz: Boolean = false,
  val emptyTrashAfterScan: Boolean = false,
  val seriesCover: String = SeriesCover.FIRST.name,
  val hashFiles: Boolean = true,
  val hashPages: Boolean = false,
  val hashKoreader: Boolean = false,
  val analyzeDimensions: Boolean = true,
  val oneshotsDirectory: String? = null,
)

internal fun XoboroLibrarySourceRequest.toSourceLocation(): SourceLocation =
  SourceLocation(
    sourceId = provider,
    itemId = location,
  )

internal fun XoboroLibrarySettingsRequest.toLibrarySettings(): LibrarySettings =
  LibrarySettings(
    importComicInfoBook = importComicInfoBook,
    importComicInfoSeries = importComicInfoSeries,
    importComicInfoCollection = importComicInfoCollection,
    importComicInfoReadList = importComicInfoReadList,
    importComicInfoSeriesAppendVolume = importComicInfoSeriesAppendVolume,
    importEpubBook = importEpubBook,
    importPdfBook = importPdfBook,
    importEpubSeries = importEpubSeries,
    importMylarSeries = importMylarSeries,
    importLocalArtwork = importLocalArtwork,
    importBarcodeIsbn = importBarcodeIsbn,
    scanForceModifiedTime = scanForceModifiedTime,
    scanOnStartup = scanOnStartup,
    scanInterval = ScanInterval.valueOf(scanInterval),
    scanCbx = scanCbx,
    scanPdf = scanPdf,
    scanEpub = scanEpub,
    scanDirectoryExclusions = scanDirectoryExclusions,
    repairExtensions = repairExtensions,
    convertToCbz = convertToCbz,
    emptyTrashAfterScan = emptyTrashAfterScan,
    seriesCover = SeriesCover.valueOf(seriesCover),
    hashFiles = hashFiles,
    hashPages = hashPages,
    hashKoreader = hashKoreader,
    analyzeDimensions = analyzeDimensions,
    oneshotsDirectory = oneshotsDirectory,
  )
