-- The cold scan was near-quadratic, and this is where the curve came from.
--
-- `catalog_search_fts` and `catalog_title_substring` declare `entity_type` and `entity_id`
-- UNINDEXED, so FTS5 has no way to seek on either. `DELETE ... WHERE entity_type = ? AND
-- entity_id = ?` therefore plans as `SCAN ... VIRTUAL TABLE INDEX 0` - a full pass over everything
-- indexed so far. Inserting one book fired four of those: the two `AFTER INSERT ON book` triggers,
-- and the two on `book_metadata` that `initialize_book_metadata` inserts into. n inserts against n
-- rows already present is O(n^2), and it measured as one - 3,000 books took 3,024 ms where 15,000
-- took 74,438 ms, 24.6x the time for 5x the books, an exponent of 1.99. Inside the scanner the same
-- statement was 97% of `cold_scan` at 15,050 items (100,160 ms of 102,738 ms).
--
-- Not one of those deletes could remove anything. `NEW.id` is a primary key that did not exist a
-- moment earlier, so nothing can be indexed under it yet. Removing them measured 74,438 ms ->
-- 377 ms at 15,000 books, and the index they built was identical row for row.
--
-- The book- and series-level insert triggers go entirely rather than merely losing their delete.
-- SQLite does not define the order in which two `AFTER INSERT` triggers on one table run, and the
-- search source views join the entity's metadata row, so whether those triggers see anything at all
-- depends on that order - keeping them without a delete would duplicate a row under one order and
-- write nothing under the other. The metadata-level triggers do not have the ambiguity:
-- `initialize_book_metadata` and `initialize_series_metadata` put exactly one metadata row behind
-- every entity, and both metadata repositories upsert with `ON CONFLICT DO UPDATE`, so an INSERT
-- trigger fires exactly once per entity. One writer, once, with nothing to delete first.
--
-- Updates and deletes still find their row by scanning. Those need a keyed lookup rather than the
-- removal of a provable no-op, so they are a separate change.

DROP TRIGGER catalog_search_book_insert;
DROP TRIGGER catalog_title_book_insert;
DROP TRIGGER catalog_search_series_insert;
DROP TRIGGER catalog_title_series_insert;

DROP TRIGGER catalog_search_book_metadata_insert;
CREATE TRIGGER catalog_search_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_book_search_source
  WHERE entity_id = NEW.book_id;
END;

DROP TRIGGER catalog_title_book_metadata_insert;
CREATE TRIGGER catalog_title_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'BOOK', entity_id, title
  FROM catalog_book_title_source
  WHERE entity_id = NEW.book_id;
END;

DROP TRIGGER catalog_search_series_metadata_insert;
CREATE TRIGGER catalog_search_series_metadata_insert
AFTER INSERT ON series_metadata
BEGIN
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'SERIES', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_series_search_source
  WHERE entity_id = NEW.series_id;
END;

DROP TRIGGER catalog_title_series_metadata_insert;
CREATE TRIGGER catalog_title_series_metadata_insert
AFTER INSERT ON series_metadata
BEGIN
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'SERIES', entity_id, title
  FROM catalog_series_title_source
  WHERE entity_id = NEW.series_id;
END;
