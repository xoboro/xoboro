# ADR 0032: Media-item organization by ordered references

## Context

Komga collections organize series while read lists organize books. Xoboro's
domain also needs to support Novel, Video, and Audio without turning those
organization features into owners of media or source paths.

## Decision

- Persist organizations as identity, name, metadata, and an ordered list of
  referenced media-item IDs. Collections currently reference series IDs and
  read lists reference book-compatible media-item IDs at the Komga boundary.
- Store ordering in normalized membership tables with unique position and
  member constraints. Replace membership transactionally on updates.
- Keep an organization after a referenced item is deleted; foreign-key cascades
  remove only the membership. This allows later repair or repopulation.
- Validate existence, active state, duplicate members, and case-insensitive
  duplicate names in the application lifecycle before persistence.
- Resolve members through the access-filtered catalog for every user-facing
  response. Hidden members are omitted and set the Komga `filtered` indicator;
  an organization with no visible members is hidden from non-administrators.
- Treat Komga DTOs and routes as adapters. The organization domain stores no
  filesystem paths and has no dependency on comic-specific media parsing.

## Consequences

Manual order survives restart and updates are atomic. Library, age, sharing
label, and future media-type restrictions remain centralized in catalog access.
Generalizing read-list membership from book-compatible IDs to every
`MediaItemId` can be done in a migration without changing organization
ownership semantics.
