# ADR 0079: Execute Series Regular Expressions in SQLite

- Status: accepted
- Date: 2026-07-28

## Context

Komga's deprecated Series list and alphabetical-group endpoints accept
`search_regex=pattern,title` and `search_regex=pattern,title_sort`. The match
is case-insensitive and participates in authorization, filtering, counting,
sorting, and pagination.

Filtering hydrated rows in application memory would produce incorrect page
counts and would scale with the full visible catalog. SQLite exposes the
`REGEXP` operator but requires the application to provide its implementation
for each physical connection.

## Decision

- Represent Series regex criteria explicitly in the framework-independent
  catalog query model.
- Parse the legacy parameter at its final comma and accept only Komga's
  `title` and `title_sort` fields.
- Register a deterministic two-argument `regexp` function once per physical
  pooled SQLite connection through a DataSource boundary.
- Use Java's Unicode-aware, case-insensitive regular-expression engine and
  cache the latest compiled expression per connection.
- Compile the criterion into the same SQL `WHERE` clause as access,
  restriction, metadata, sort, count, and page predicates.
- Reuse the query for deprecated Series alphabetical groups.

## Consequences

Regex results and total counts remain database-paged and authorization-aware.
No full-catalog hydration or secondary filtering pass is introduced. New and
recreated pool connections receive the function before their first Xoboro
statement, while repeated checkouts do not attempt duplicate registration.

Invalid expressions surface as database query errors, matching Komga's
existing legacy endpoint behavior.
