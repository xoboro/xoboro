package io.xoboro.core.application

import io.xoboro.core.domain.User

interface RememberMeTokenService {
  fun issue(user: User): String

  fun authenticate(encodedToken: String): User?
}
