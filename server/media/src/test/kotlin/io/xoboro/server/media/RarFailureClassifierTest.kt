package io.xoboro.server.media

import com.github.junrar.exception.BadRarArchiveException
import com.github.junrar.exception.CorruptHeaderException
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.NotRarArchiveException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarMethodException
import com.github.junrar.exception.WrongPasswordException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the failure-to-diagnosis mapping against real junrar exception instances.
 *
 * These are the exceptions themselves rather than an archive that provokes them: nothing in the
 * dependency set can write a password-protected or multi-volume RAR, and no `rar` binary is assumed
 * to exist on the machine running the suite. What stays outside this test is the claim that junrar
 * raises each one for the situation its name describes - that is the library's contract, not ours.
 */
class RarFailureClassifierTest {
  private val classifier = RarFailureClassifier()

  @Test
  fun `treats every encryption failure as encrypted`() {
    assertEquals(RarFailure.ENCRYPTED, classifier.classify(UnsupportedRarEncryptedException()))
    assertEquals(RarFailure.ENCRYPTED, classifier.classify(WrongPasswordException()))
    assertEquals(RarFailure.ENCRYPTED, classifier.classify(InitDeciphererFailedException()))
  }

  @Test
  fun `treats a missing volume in either direction as an incomplete set`() {
    assertEquals(
      RarFailure.INCOMPLETE_VOLUME_SET,
      classifier.classify(MissingNextVolumeException("part2.rar")),
    )
    assertEquals(
      RarFailure.INCOMPLETE_VOLUME_SET,
      classifier.classify(MissingPreviousVolumeException("part1.rar")),
    )
  }

  @Test
  fun `treats damage and unknown failures as unreadable`() {
    assertEquals(RarFailure.UNREADABLE, classifier.classify(CorruptHeaderException()))
    assertEquals(RarFailure.UNREADABLE, classifier.classify(CrcErrorException()))
    assertEquals(RarFailure.UNREADABLE, classifier.classify(BadRarArchiveException()))
    assertEquals(RarFailure.UNREADABLE, classifier.classify(NotRarArchiveException()))
    assertEquals(RarFailure.UNREADABLE, classifier.classify(UnsupportedRarMethodException()))
    assertEquals(RarFailure.UNREADABLE, classifier.classify(IOException("truncated")))
  }
}
