package io.xoboro.core.application

import io.xoboro.core.domain.User

/**
 * Projects a user onto the authorization envelope that every catalog read must carry.
 *
 * This projection is the only place that decides what a user is allowed to see, so it lives beside
 * [CatalogAccess] rather than beside any one interface. It used to exist as five byte-identical
 * copies under five names — `catalogAccess`, `nativeCatalogAccess`, `mediaAccess`, `progressAccess`
 * and `tachiyomiAccess` — one per route group. Copies are the wrong shape for an authorization rule:
 * adding a dimension to [io.xoboro.core.domain.ContentRestrictions] would have required five edits,
 * and forgetting one would have left that interface silently under-enforcing while every test that
 * exercised the other four still passed.
 *
 * A null `libraryIds` means "no library filter", which is why administrators and users who share
 * every library are projected to null rather than to the set of every library id: the set would go
 * stale the moment a library is added.
 */
fun User.catalogAccess(): CatalogAccess =
  CatalogAccess(
    userId = id,
    libraryIds = if (canAccessAllLibraries()) null else sharedLibraryIds,
    restrictions = restrictions,
  )
