package io.xoboro.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserTest {
  @Test
  fun `normalizes sharing labels and exclusion wins conflicts`() {
    val restrictions =
      ContentRestrictions(
        labelsAllow = setOf(" Family ", "Blocked", ""),
        labelsExclude = setOf("BLOCKED", " Mature "),
      )

    assertEquals(setOf("family"), restrictions.labelsAllow)
    assertEquals(setOf("blocked", "mature"), restrictions.labelsExclude)
    assertTrue(restrictions.isRestricted)
  }

  @Test
  fun `applies Komga-compatible allow and exclude semantics`() {
    val allowOnly =
      user(
        restrictions =
          ContentRestrictions(
            ageRestriction = AgeRestriction(12, RestrictionMode.ALLOW_ONLY),
            labelsAllow = setOf("family"),
          ),
      )
    assertTrue(allowOnly.isContentAllowed(ageRating = 16, sharingLabels = setOf("Family")))
    assertTrue(allowOnly.isContentAllowed(ageRating = 10))
    assertFalse(allowOnly.isContentAllowed(ageRating = 16))

    val excluded =
      user(
        restrictions =
          ContentRestrictions(
            ageRestriction = AgeRestriction(18, RestrictionMode.EXCLUDE),
            labelsExclude = setOf("restricted"),
          ),
      )
    assertTrue(excluded.isContentAllowed(ageRating = 12))
    assertFalse(excluded.isContentAllowed(ageRating = 18))
    assertFalse(excluded.isContentAllowed(sharingLabels = setOf("RESTRICTED")))
  }

  @Test
  fun `admins always access every library`() {
    val admin = user(roles = setOf(UserRole.ADMIN), sharesAllLibraries = false)
    val restricted =
      user(
        roles = emptySet(),
        sharesAllLibraries = false,
        sharedLibraryIds = setOf(LibraryId("library-1")),
      )

    assertTrue(admin.canAccessLibrary(LibraryId("other")))
    assertTrue(restricted.canAccessLibrary(LibraryId("library-1")))
    assertFalse(restricted.canAccessLibrary(LibraryId("other")))
  }

  @Test
  fun `rejects invalid identity and credential state`() {
    assertFailsWith<IllegalArgumentException> {
      user(email = "invalid")
    }
    assertFailsWith<IllegalArgumentException> {
      user(passwordHash = "")
    }
    assertFailsWith<IllegalArgumentException> {
      AgeRestriction(-1, RestrictionMode.EXCLUDE)
    }
  }

  private fun user(
    email: String = "reader@example.invalid",
    passwordHash: String = "synthetic-hash",
    roles: Set<UserRole> = emptySet(),
    sharesAllLibraries: Boolean = true,
    sharedLibraryIds: Set<LibraryId> = emptySet(),
    restrictions: ContentRestrictions = ContentRestrictions(),
  ): User =
    User(
      id = UserId("user-1"),
      email = email,
      passwordHash = passwordHash,
      roles = roles,
      sharesAllLibraries = sharesAllLibraries,
      sharedLibraryIds = sharedLibraryIds,
      restrictions = restrictions,
      createdAtMillis = 1,
    )
}
