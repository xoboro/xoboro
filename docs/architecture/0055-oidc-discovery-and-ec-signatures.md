# ADR 0055: OIDC discovery and EC signatures

## Status

Accepted.

## Context

Requiring every OIDC endpoint duplicates provider metadata and makes endpoint
rotation an operator task. Providers also commonly sign ID tokens with ECDSA,
whose JOSE signature representation is a fixed-width `r || s` value rather
than the ASN.1 DER sequence consumed by Java cryptography.

Discovery and signature flexibility must not weaken issuer, algorithm, or key
binding. A malicious discovery document, mismatched JWK type, or algorithm
confusion must fail before an identity is accepted.

## Decision

- Permit an OIDC registration to contain only its issuer, client credentials,
  and an `openid` scope. Ordinary OAuth2 registrations still require explicit
  authorization, token, and user-info endpoints.
- Fetch `<issuer>/.well-known/openid-configuration` on first authorization,
  require an exact issuer match, validate every discovered endpoint as an HTTP
  or HTTPS URI with a host, and cache the document per configured issuer.
- Prefer explicitly configured endpoints and use discovery only for missing
  authorization, token, user-info, or JWK-set values.
- Keep provider I/O suspendable through the portable OAuth boundary so a
  discovery request never requires blocking a server thread.
- Accept RS256, RS384, RS512, ES256, ES384, and ES512. Bind each algorithm to
  its required RSA or EC JWK type and bind EC algorithms to P-256, P-384, or
  P-521 respectively.
- Enforce optional JWK `alg`, `use`, and `key_ops` restrictions. Convert exact
  fixed-width JOSE ECDSA signatures into positive, minimally encoded DER
  integers before verification.
- Preserve all issuer, audience, authorized-party, time, subject, and
  constant-time nonce checks after cryptographic verification.

## Consequences

Operators can migrate issuer-based Spring Security OIDC registrations without
copying provider endpoints. Explicit configurations remain deterministic and
do not perform discovery. RSA and the three standard NIST EC curves share one
strict verification path, covered with generated synthetic keys and discovery
documents; provider-network differential certification remains separate.
