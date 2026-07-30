package io.xoboro.core.application

/**
 * What an external OAuth2 or OIDC identity is allowed to do when its email already belongs to a local
 * account.
 *
 * This decision used to be implicit. `complete()` looked the email up and, on a hit, returned that
 * account — so any provider identity asserting `reader@example.com` signed in as the local
 * `reader@example.com`, whatever the provider had actually checked. That is fine when the provider
 * verifies email ownership and terrible when it does not: an attacker who can register
 * `admin@company.test` at a permissive provider takes over the local administrator.
 *
 * The exposure is narrower than it first looks, and the policy is worth having precisely because of
 * where it is *not* narrow:
 *
 * - With OIDC **and** email verification required, an unverified email never reaches the lookup, so
 *   [VERIFIED_EMAIL] and [EMAIL] behave identically.
 * - With OIDC and verification **not** required, or with a plain OAuth2 provider — which has no
 *   verification claim at all — nothing stood between an asserted email and an existing account.
 *
 * So this matters exactly in the configurations an operator is most likely to reach for while getting
 * a provider working, which is the worst time for a silent policy.
 */
enum class OAuth2AccountLinking {
  /**
   * Link only when the provider asserted that it verified the email. A plain OAuth2 provider makes no
   * such assertion, so under this policy it can never link to an existing account — only create a new
   * one, and only if account creation is enabled.
   *
   * The default. It is the only value that is safe without knowing which provider is configured.
   */
  VERIFIED_EMAIL,

  /**
   * Link on an email match regardless of what the provider verified. This is Komga's behaviour and
   * exists so a migrating deployment can keep it, but choosing it has to be deliberate: it makes the
   * provider's word on email ownership sufficient to enter an existing account.
   */
  EMAIL,

  /**
   * Never link. An external identity whose email already belongs to a local account is refused rather
   * than signed in, and rather than silently creating a duplicate — which is why this is a refusal and
   * not a fall-through to account creation.
   *
   * For deployments where local accounts and provider accounts are meant to stay separate populations.
   */
  NEVER,
  ;

  /**
   * Whether an identity may enter an existing local account.
   *
   * [emailVerified] is the provider's claim, null when it made none.
   */
  fun permitsLinking(emailVerified: Boolean?): Boolean =
    when (this) {
      VERIFIED_EMAIL -> emailVerified == true
      EMAIL -> true
      NEVER -> false
    }
}
