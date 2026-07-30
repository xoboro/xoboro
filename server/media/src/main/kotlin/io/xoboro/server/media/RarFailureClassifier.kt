package io.xoboro.server.media

import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException

/**
 * What a RAR archive's failure says about the archive.
 *
 * [UNREADABLE] is the only kind that describes a damaged file. The other two describe files that are
 * intact, which is why they must not share a diagnosis with it.
 */
enum class RarFailure {
  ENCRYPTED,
  INCOMPLETE_VOLUME_SET,
  UNREADABLE,
}

/**
 * Sorts a failure raised while reading a RAR archive into a [RarFailure].
 *
 * junrar names its failure modes precisely and then throws them all as one supertype. Collapsing
 * them into a single error code discarded that naming and reported "damaged" for archives that were
 * perfectly intact - a password-protected one, or one volume of a set.
 *
 * The classification is separate from the analyzer because it can be tested against real exception
 * instances. Building a RAR archive that makes junrar raise each of these is not something the
 * dependency set can do, so the alternative was an untested `when`.
 */
class RarFailureClassifier {
  fun classify(failure: Throwable): RarFailure =
    when (failure) {
      is UnsupportedRarEncryptedException,
      is WrongPasswordException,
      is InitDeciphererFailedException,
      -> RarFailure.ENCRYPTED
      is MissingNextVolumeException,
      is MissingPreviousVolumeException,
      -> RarFailure.INCOMPLETE_VOLUME_SET
      else -> RarFailure.UNREADABLE
    }
}
