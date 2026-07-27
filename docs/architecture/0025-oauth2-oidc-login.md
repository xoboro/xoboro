# ADR 0025: OAuth2 and OIDC login boundary

- Status: accepted
- Date: 2026-07-27

## Context

Komga delegates browser login to Spring Security registrations. Xoboro must
retain provider discovery, callback URLs, account matching and creation policy,
session issuance, and Komga's OAuth error behavior without coupling portable
user policy to Ktor or a specific HTTP client.

Authorization state alone is not sufficient login-CSRF protection when it is
stored globally: an attacker could initiate a valid flow and send that callback
to another browser. OIDC also requires cryptographic ID-token validation before
user-info claims are trusted.

## Decision

- Keep registrations, pending-authorization ports, external identities, and
  account policy in the portable application module.
- Bind every random, one-time authorization state to a separate random
  HTTP-only, SameSite=Lax browser cookie. Consume state on the first callback
  and compare the binding in constant time.
- Add an OIDC nonce and require it in the signed ID token.
- Verify RSA-signed ID tokens against the provider JWK set. Validate algorithm,
  key ID, signature, issuer, audience, authorized party, subject, expiry,
  not-before, issued-at, and nonce before requesting user information.
- Refresh the JWK set when an unknown key ID appears, allowing safe provider key
  rotation without restarting.
- Match users by case-insensitive email. Preserve Komga's optional account
  creation and OIDC email-verification policies and `ERR_1024` through
  `ERR_1028` failure codes.
- Issue the existing Komga-compatible session cookie after a successful login
  and redirect to the same server-redirect locations as Komga.
- Parse Spring Security registration environment names so an existing Komga
  OAuth deployment can migrate configuration. Built-in GitHub, Google, and
  Facebook provider endpoints are available; custom providers supply explicit
  endpoint and JWK settings.
- Redact client secrets from registration string representations and never
  include upstream exception details in browser redirect URLs.

## Consequences

The HTTP exchange and crypto adapter can evolve independently from account
policy and future native clients. RSA-based OIDC and ordinary OAuth2 providers
are operational. EC-signed ID tokens, issuer discovery, exact reverse-proxy
origin trust, and differential tests against configured real providers remain
release-gate work and are tracked as partial compatibility.
