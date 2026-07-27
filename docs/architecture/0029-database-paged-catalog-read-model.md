# ADR 0029: Database-paged catalog read model

## Context

Komga catalog responses combine catalog identity, media analysis, metadata,
series aggregation, user library grants, and content restrictions. Loading a
large library into application memory before filtering or paging would make
request cost proportional to the complete collection and recreate a major
scalability problem.

Xoboro's canonical hierarchy remains `Library` to `MediaItem`, with Comic,
Novel, Book, Video, and Audio specializations. Komga's Book and Series shapes
are compatibility wire models rather than the domain root.

## Decision

- Define portable catalog query, page, sort, access, and aggregate models in
  the application module.
- Execute library grants, age rules, sharing-label rules, search, filters,
  sorting, counting, and pagination in SQLite before hydrating media items.
- Allowlist every sort column and bind all request values. Escape SQL `LIKE`
  wildcards so user text cannot alter matching semantics.
- Use stable metadata number, relative path, and ID tie-breakers for sibling
  navigation.
- Hydrate only the selected page with media and metadata relations.
- Map the result to Komga DTOs inside the compatibility adapter. Non-admin
  callers never receive source paths.
- Reject unsupported recursive structured search conditions instead of
  silently returning an incorrectly filtered catalog.

## Consequences

Catalog list memory usage is bounded by requested page size rather than total
library size. Access restrictions affect counts and pages at the database
boundary, preventing sparse pages and hidden-item count leaks. The same query
port can later expose Xoboro-native Mobile, Video, and Audio DTOs without
coupling them to Komga's Book representation.

Recursive Komga search conditions, read-progress-derived counts and feeds,
full-text indexes, and differential response fixtures remain explicit follow-up
work.
