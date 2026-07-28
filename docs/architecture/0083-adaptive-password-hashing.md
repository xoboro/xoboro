# ADR 0083: Adaptive password hashing

- Status: accepted
- Date: 2026-07-28

## Context

Xoboro must authenticate users imported from Komga without inheriting Komga's internal
security choices indefinitely. Komga 1.25.0 stores BCrypt hashes, while current OWASP
guidance recommends Argon2id for new password storage and treats BCrypt as a legacy
compatibility option.

Ktor provides authentication protocol plugins but intentionally does not provide a
password-storage algorithm. Pulling Spring Security Crypto into a Ktor server only for
BCrypt adds a framework-specific dependency without improving the HTTP boundary.

## Decision

- Use Ktor's official credential and API-key authentication providers at the HTTP
  boundary wherever their challenge behavior can represent the Komga contract.
- Keep hashing behind the portable `PasswordHasher` application port.
- Use Password4j's Argon2id implementation for new and changed passwords with OWASP's
  minimum profile: 19 MiB memory, two iterations, one lane, and a 32-byte output.
- Continue verifying imported BCrypt hashes with Password4j. After a successful login,
  replace the stored hash with Argon2id using a compare-and-set database update.
- Rehash valid Argon2id credentials when their stored parameters no longer match the
  configured profile.
- Reject unknown or malformed hash formats without exposing the reason to clients.
- Do not expire active sessions solely because the same verified password received a
  stronger storage hash.

## Consequences

Existing Komga users can log in without resetting their passwords, while Xoboro does
not create new BCrypt credentials. Concurrent password changes cannot be overwritten
by a login-time upgrade because persistence only replaces the exact hash that was
verified. Spring framework artifacts are not part of Xoboro's authentication runtime.
