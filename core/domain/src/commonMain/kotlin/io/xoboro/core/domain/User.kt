package io.xoboro.core.domain

data class UserId(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "User ID must not be blank" }
  }
}

enum class UserRole {
  ADMIN,
  FILE_DOWNLOAD,
  PAGE_STREAMING,
  KOBO_SYNC,
  KOREADER_SYNC,
}

enum class RestrictionMode {
  ALLOW_ONLY,
  EXCLUDE,
}

data class AgeRestriction(
  val age: Int,
  val mode: RestrictionMode,
) {
  init {
    require(age >= 0) { "Age restriction must not be negative" }
  }
}

class ContentRestrictions(
  val ageRestriction: AgeRestriction? = null,
  labelsAllow: Set<String> = emptySet(),
  labelsExclude: Set<String> = emptySet(),
) {
  val labelsExclude: Set<String> = labelsExclude.normalizedLabels()
  val labelsAllow: Set<String> = labelsAllow.normalizedLabels() - this.labelsExclude

  val isRestricted: Boolean
    get() = ageRestriction != null || labelsAllow.isNotEmpty() || labelsExclude.isNotEmpty()

  override fun equals(other: Any?): Boolean =
    other is ContentRestrictions &&
      ageRestriction == other.ageRestriction &&
      labelsAllow == other.labelsAllow &&
      labelsExclude == other.labelsExclude

  override fun hashCode(): Int {
    var result = ageRestriction?.hashCode() ?: 0
    result = 31 * result + labelsAllow.hashCode()
    result = 31 * result + labelsExclude.hashCode()
    return result
  }

  override fun toString(): String =
    "ContentRestrictions(ageRestriction=$ageRestriction, labelsAllow=$labelsAllow, " +
      "labelsExclude=$labelsExclude)"

  private fun Set<String>.normalizedLabels(): Set<String> =
    asSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .map(String::lowercase)
      .toSet()
}

data class User(
  val id: UserId,
  val email: String,
  val passwordHash: String,
  val roles: Set<UserRole> = setOf(UserRole.FILE_DOWNLOAD, UserRole.PAGE_STREAMING),
  val sharedLibraryIds: Set<LibraryId> = emptySet(),
  val sharesAllLibraries: Boolean = true,
  val restrictions: ContentRestrictions = ContentRestrictions(),
  val createdAtMillis: Long,
  val updatedAtMillis: Long = createdAtMillis,
) {
  init {
    require(email.isNotBlank()) { "User email must not be blank" }
    require(email == email.trim() && EMAIL_PATTERN.matches(email)) {
      "User email must be valid"
    }
    require(passwordHash.isNotBlank()) { "User password hash must not be blank" }
    require(createdAtMillis >= 0) { "Created timestamp must not be negative" }
    require(updatedAtMillis >= createdAtMillis) {
      "Updated timestamp must not precede created timestamp"
    }
  }

  val isAdmin: Boolean
    get() = UserRole.ADMIN in roles

  fun canAccessAllLibraries(): Boolean = isAdmin || sharesAllLibraries

  fun canAccessLibrary(libraryId: LibraryId): Boolean =
    canAccessAllLibraries() || libraryId in sharedLibraryIds

  fun isContentAllowed(
    ageRating: Int? = null,
    sharingLabels: Set<String> = emptySet(),
  ): Boolean {
    val labels =
      sharingLabels
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()
    val ageAllowed =
      if (restrictions.ageRestriction?.mode == RestrictionMode.ALLOW_ONLY) {
        ageRating != null && ageRating <= restrictions.ageRestriction.age
      } else {
        null
      }
    val labelAllowed =
      if (restrictions.labelsAllow.isNotEmpty()) {
        restrictions.labelsAllow.intersect(labels).isNotEmpty()
      } else {
        null
      }
    val allowed =
      when {
        ageAllowed == null -> labelAllowed != false
        labelAllowed == null -> ageAllowed != false
        else -> ageAllowed || labelAllowed
      }
    if (!allowed) return false

    val ageDenied =
      restrictions.ageRestriction?.let { restriction ->
        restriction.mode == RestrictionMode.EXCLUDE &&
          ageRating != null &&
          ageRating >= restriction.age
      } ?: false
    val labelDenied = restrictions.labelsExclude.intersect(labels).isNotEmpty()
    return !ageDenied && !labelDenied
  }

  companion object {
    private val EMAIL_PATTERN = Regex(".+@.+\\..+")
  }
}

interface UserRepository {
  fun count(): Long

  fun findByIdOrNull(id: UserId): User?

  fun findByEmailIgnoreCaseOrNull(email: String): User?

  fun findAll(): List<User>

  fun insert(user: User)

  fun claimIfEmpty(user: User): Boolean

  fun update(user: User)

  fun replacePasswordHash(
    id: UserId,
    expectedHash: String,
    replacementHash: String,
    updatedAtMillis: Long,
  ): Boolean {
    val user = findByIdOrNull(id) ?: return false
    if (user.passwordHash != expectedHash) return false
    update(user.copy(passwordHash = replacementHash, updatedAtMillis = updatedAtMillis))
    return true
  }

  fun delete(id: UserId)
}

class UserEmailAlreadyExistsException(
  email: String,
) : IllegalArgumentException("A user with this email already exists: $email")

class ServerAlreadyClaimedException : IllegalStateException("This server has already been claimed")
