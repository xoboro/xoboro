package io.xoboro.server.metadata

/**
 * Why a `series.json` did not import, or imported with something ignored.
 *
 * These exist because the provider used to wrap everything in `runCatching { … }.getOrNull()`. A
 * `series.json` with a typo, a truncated write, or a field the schema does not define behaved exactly
 * like a directory with no sidecar at all: nothing happened and nothing said so. An operator who had
 * just written the file had no way to tell "Xoboro ignored my file" from "Xoboro never looked".
 *
 * Deliberately a closed set rather than a message string. A caller decides how loudly to report each
 * one, and [SeriesJsonIgnored] in particular should not be an error: a newer Mylar writing a field this
 * version does not read is normal, and treating schema drift as a failure would make every upgrade look
 * broken.
 */
sealed interface MylarSeriesDiagnostic {
  /** The path reported to the operator, so a message names the file they wrote. */
  val seriesItemId: String

  /** The sidecar exists but could not be read as bytes. */
  data class SeriesJsonUnreadable(
    override val seriesItemId: String,
    val reason: String,
  ) : MylarSeriesDiagnostic

  /** The bytes are not JSON. Almost always a truncated write or a hand-edit. */
  data class SeriesJsonMalformed(
    override val seriesItemId: String,
    val reason: String,
  ) : MylarSeriesDiagnostic

  /**
   * Valid JSON, but not a Mylar `series.json`: no top-level `metadata` object.
   *
   * Separate from [SeriesJsonMalformed] because the fix is different — a malformed file needs
   * rewriting, this one is the wrong kind of file under the right name.
   */
  data class SeriesJsonNotMylar(
    override val seriesItemId: String,
  ) : MylarSeriesDiagnostic

  /**
   * A Mylar `series.json` with no usable `name`.
   *
   * The one genuinely required field: everything else this provider reads is optional, and a patch
   * with no title would overwrite nothing while reporting an import.
   */
  data class SeriesJsonMissingName(
    override val seriesItemId: String,
  ) : MylarSeriesDiagnostic

  /**
   * A field was present but its value could not be understood, so that one field was skipped.
   *
   * The import still happens. One unparsable `age_rating` should not cost the operator their title,
   * publisher and summary.
   */
  data class SeriesJsonFieldIgnored(
    override val seriesItemId: String,
    val field: String,
    val value: String,
  ) : MylarSeriesDiagnostic

  /**
   * The file carries fields this version does not read.
   *
   * Reported at the lowest severity a caller has. A newer Mylar writing new fields is expected, and
   * treating it as a failure would make every Mylar upgrade look like a broken import.
   */
  data class SeriesJsonIgnored(
    override val seriesItemId: String,
    val fields: Set<String>,
  ) : MylarSeriesDiagnostic
}

/**
 * Where a provider reports diagnostics.
 *
 * A sink rather than a return value, because a diagnostic is not the provider's product: the provider
 * returns a metadata patch, and reporting must not change whether that patch is produced. It also keeps
 * `SeriesMetadataProvider` unchanged for the providers that have nothing to report.
 */
fun interface MylarSeriesDiagnosticSink {
  fun report(diagnostic: MylarSeriesDiagnostic)

  companion object {
    /** Discards everything, which is what a caller that has nowhere to put diagnostics should do. */
    val NONE: MylarSeriesDiagnosticSink = MylarSeriesDiagnosticSink { }
  }
}
