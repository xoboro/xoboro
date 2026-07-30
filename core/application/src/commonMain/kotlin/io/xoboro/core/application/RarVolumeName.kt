package io.xoboro.core.application

/**
 * A file name recognised as one part of a multi-volume RAR set.
 *
 * [stem] is the name shared by every volume in the set, so two names belong to the same set exactly
 * when their stems are equal. [number] is the volume's position, 1-based.
 */
data class RarVolumeName(
  val stem: String,
  val number: Int,
) {
  init {
    require(stem.isNotBlank()) { "Volume stem must not be blank" }
    require(number >= 1) { "Volume number must be at least one" }
  }

  val isFirst: Boolean get() = number == 1
}

/**
 * Recognises the `name.partN.rar` multi-volume naming scheme.
 *
 * A multi-volume RAR set is one logical archive split across files. Only the first volume opens as an
 * archive; the rest are continuations, and each was surfacing as its own book with a broken media
 * item. This parses the name so a scan can tell the difference.
 *
 * Deliberately narrow, on two axes:
 *
 * - **Only the `.partN` scheme.** RAR 2's older scheme names continuations `.r00`, `.r01`, `.s00` and
 *   so on, and those extensions are not scanned at all, so those volumes never became candidates.
 *   That exclusion is incidental rather than intentional, which is why it is stated here.
 * - **`part` must not be preceded by a space or separator.** A comic legitimately titled
 *   `Series - part 2.cbr` or `Series part 2.cbr` is a whole book, not a volume, and matching those
 *   would make a real book disappear. Only the tool-generated `Series.part2.rar` shape is recognised.
 *
 * Recognising a name is not on its own a reason to suppress it: a lone `x.part2.rar` with no
 * `x.part1.rar` beside it is far more likely to be an oddly named book than half a set. The caller
 * decides, using [stem] to find siblings.
 */
object RarVolumeNames {
  /** Returns the parsed volume, or null when [name] is not a `.partN.rar`-style volume name. */
  fun parseOrNull(name: String): RarVolumeName? {
    val match = PATTERN.matchEntire(name) ?: return null
    val (stem, digits, extension) = match.destructured
    // A leading-zero form (`part01`) is normal and means the set has ten or more volumes; the numeric
    // value is what identifies the position, so `part1` and `part01` are the same volume.
    val number = digits.toIntOrNull() ?: return null
    if (number < 1) return null
    // The extension stays in the stem so that a `.part1.rar` and a `.part2.cbr` are not treated as one
    // set. Mixed extensions across one set do not occur from any tool, and treating them as related
    // would let one oddly named file suppress another.
    return RarVolumeName(stem = "$stem.$extension", number = number)
  }

  private val PATTERN = Regex("""^(.+?)(?<![\s._-])\.part(\d{1,3})\.(rar|cbr)$""", RegexOption.IGNORE_CASE)
}
