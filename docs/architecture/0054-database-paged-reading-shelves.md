# ADR 0054: Database-paged reading shelves

## Status

Accepted.

## Context

Reader shelves must remain correct when a catalog grows beyond an HTTP page
limit. Loading a bounded catalog page, filtering it in application memory, and
then paginating the result truncates eligible items, reports an incorrect
total, and allocates work proportional to the artificial boundary.

Keep-reading also depends on user-specific progress and analyzed-media state.
Both are already durable and indexed in the database, so reconstructing the
shelf after hydrating unrelated catalog records wastes I/O and CPU.

## Decision

- Express keep-reading as a `BookCatalogQuery` capability in the application
  read-model contract.
- Join the requesting user's incomplete progress and READY media rows directly
  into the SQL catalog selection.
- Apply library authorization, content restrictions, count, newest-read
  ordering, stable ID tie-breaking, limit, and offset in the database before
  hydrating books.
- Return an empty shelf for callers without an authenticated user.
- Keep explicit client sorts authoritative; otherwise order by progress read
  time descending and book ID ascending.
- Exercise the query with more than 10,000 synthetic eligible records and
  verify the first page, the last page, exact count, excluded completed/error
  records, anonymous behavior, and OPDS projection.

## Consequences

The shelf no longer has a hidden maximum catalog size and its total matches the
same authorized predicate used for content. SQLite can start from the existing
user/read-date progress index, join books and media by their keys, and hydrate
only the requested page. Other user-specific shelves should follow the same
query-capability pattern rather than post-filtering bounded lists.
