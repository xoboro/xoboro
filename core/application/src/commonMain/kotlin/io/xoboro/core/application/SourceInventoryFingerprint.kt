package io.xoboro.core.application

data class SourceInventoryFingerprint(
  val value: String,
  val failedEntries: Long,
) {
  init {
    require(value.isNotBlank()) { "Source inventory fingerprint must not be blank" }
    require(failedEntries >= 0) { "Fingerprint failure count must not be negative" }
  }
}

/** A source that can cheaply describe the metadata set a regular inventory would emit. */
interface FingerprintingSourceInventory : SourceInventory {
  fun fingerprint(
    rootItemId: String,
    directoryExclusions: Set<String> = emptySet(),
  ): SourceInventoryFingerprint
}
