-- SQLite fires `AFTER UPDATE OF <column>` whenever the column appears in the SET clause, whether or
-- not the value changed. Both search triggers therefore rebuilt a full-text row for every book a bulk
-- update touched, and rebuilding one row evaluates a view with three joins and five correlated
-- group_concat subqueries before FTS5 re-tokenises the result. A library re-scan writes every matched
-- book, so a scan that changed nothing still paid for the whole catalogue: measured at 43 minutes of
-- CPU inside a single write transaction against 111,745 books, which holds the one SQLite write lock
-- long enough to starve every other writer in the process.
--
-- Guarding on an actual value change makes a no-op write free. Nothing else about the triggers moves.

DROP TRIGGER catalog_search_book_update;

CREATE TRIGGER catalog_search_book_update
AFTER UPDATE OF name, series_id ON book
WHEN NEW.name IS NOT OLD.name OR NEW.series_id IS NOT OLD.series_id
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'BOOK' AND entity_id = NEW.id;
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_book_search_source
  WHERE entity_id = NEW.id;
END;

-- The series trigger is the more expensive of the two: renaming one series rebuilds a full-text row
-- for every book under it, so an unguarded no-op write costs the whole series.
DROP TRIGGER catalog_search_series_update;

CREATE TRIGGER catalog_search_series_update
AFTER UPDATE OF name ON series
WHEN NEW.name IS NOT OLD.name
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'SERIES' AND entity_id = NEW.id;
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'SERIES', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_series_search_source
  WHERE entity_id = NEW.id;
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'BOOK' AND entity_id IN (
    SELECT id FROM book WHERE series_id = NEW.id
  );
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_book_search_source
  WHERE entity_id IN (SELECT id FROM book WHERE series_id = NEW.id);
END;
