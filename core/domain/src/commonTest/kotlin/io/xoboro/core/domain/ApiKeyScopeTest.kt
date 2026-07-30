package io.xoboro.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiKeyScopeTest {
  @Test
  fun `an unscoped key leaves the owner's capabilities untouched`() {
    val owner = owner(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING)

    assertEquals(owner, key().scope(owner))
  }

  @Test
  fun `a scoped key narrows the owner to the named roles`() {
    val owner = owner(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING, UserRole.KOBO_SYNC)

    val scoped = key(scopes = setOf(UserRole.PAGE_STREAMING)).scope(owner)

    assertEquals(setOf(UserRole.PAGE_STREAMING), scoped.roles)
    // Only roles are narrowed. Library grants and content restrictions belong to the account, and a
    // key that quietly widened or narrowed them would be a second, competing definition of access.
    assertEquals(owner.sharedLibraryIds, scoped.sharedLibraryIds)
    assertEquals(owner.restrictions, scoped.restrictions)
  }

  @Test
  fun `a scope cannot grant a role the owner no longer holds`() {
    // The key was issued while the owner was an administrator; the role has since been taken away.
    val demoted = owner(UserRole.PAGE_STREAMING)

    val scoped = key(scopes = setOf(UserRole.ADMIN, UserRole.PAGE_STREAMING)).scope(demoted)

    assertEquals(setOf(UserRole.PAGE_STREAMING), scoped.roles)
    assertFalse(scoped.isAdmin)
  }

  @Test
  fun `a scope disjoint from the owner's roles grants nothing`() {
    val scoped = key(scopes = setOf(UserRole.KOBO_SYNC)).scope(owner(UserRole.PAGE_STREAMING))

    assertEquals(emptySet(), scoped.roles)
  }

  @Test
  fun `expiry is inclusive of the instant it names`() {
    val expiring = key(expiresAtMillis = 100)

    assertFalse(expiring.hasExpired(99))
    // At the named instant the key is already gone: a key said to expire at 100 must not still work
    // at 100.
    assertTrue(expiring.hasExpired(100))
    assertTrue(expiring.hasExpired(101))
  }

  @Test
  fun `a key without an expiry never expires`() {
    assertFalse(key().hasExpired(Long.MAX_VALUE))
  }

  @Test
  fun `an expiry at or before creation is rejected`() {
    assertFailsWith<IllegalArgumentException> { key(expiresAtMillis = 10) }
    assertFailsWith<IllegalArgumentException> { key(expiresAtMillis = 1) }
  }

  private fun key(
    scopes: Set<UserRole> = emptySet(),
    expiresAtMillis: Long? = null,
  ): ApiKey =
    ApiKey(
      id = ApiKeyId("key-1"),
      userId = UserId("user-1"),
      keyHash = "synthetic-hash",
      comment = "Synthetic client",
      scopes = scopes,
      expiresAtMillis = expiresAtMillis,
      createdAtMillis = 10,
    )

  private fun owner(vararg roles: UserRole): User =
    User(
      id = UserId("user-1"),
      email = "reader@example.invalid",
      passwordHash = "synthetic-hash",
      roles = roles.toSet(),
      sharedLibraryIds = setOf(LibraryId("library-1")),
      sharesAllLibraries = false,
      restrictions = ContentRestrictions(labelsExclude = setOf("synthetic")),
      createdAtMillis = 1,
    )
}
