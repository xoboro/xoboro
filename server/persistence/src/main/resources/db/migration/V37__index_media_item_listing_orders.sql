-- A media-item page sorted 145,105 rows to return 24 of them.
--
-- Every listing order closes with the item's id so that paging is deterministic, and none of the
-- resulting keys had an index. Measured against the live catalog: `ORDER BY b.created_at_ms, b.id
-- LIMIT 24` planned as `SCAN b` + `USE TEMP B-TREE FOR ORDER BY` and cost 92 ms, while the same
-- shape on an already-indexed column (`ORDER BY b.series_id`) planned as `SCAN b USING INDEX` and
-- cost 0 ms. The work is proportional to the whole table rather than to the page, so it grows with
-- the library while the page stays 24 rows.
--
-- Each index carries the id, because the ORDER BY does. Without it SQLite can seek the first
-- column and then still has to sort within every group of equal timestamps, which for `file_size`
-- is most of the table.
--
-- Ascending only, deliberately. SQLite reads an index backwards for a fully-reversed order, so one
-- ascending index serves both `ASC, ASC` and `DESC, DESC`; it is a *mixed* order that defeats it.
-- The listing orders are fully-reversible for exactly that reason - the tie-breaker follows the
-- direction of the sort it closes.
--
-- The columns are the book-local listing sorts: `createdAt`, `updatedAt`, `sourceModifiedAt` and
-- `fileSize`. The orders that read a joined table - title, number, series title, last read - are
-- not served here, since no index on `book` can span them.
--
-- Series is not indexed alongside: at 3,339 rows its listing counts in 2 ms and pages in 6 ms, so
-- the temp sort there is bounded by a table three orders of magnitude smaller.

CREATE INDEX book_created_at_idx ON book (created_at_ms, id);
CREATE INDEX book_updated_at_idx ON book (updated_at_ms, id);
CREATE INDEX book_file_modified_idx ON book (file_modified_ms, id);
CREATE INDEX book_file_size_idx ON book (file_size, id);
