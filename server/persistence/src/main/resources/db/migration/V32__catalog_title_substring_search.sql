-- `catalog_search_fts` tokenises with `unicode61`, which splits text on non-alphanumeric boundaries
-- and then only matches at a token's start, because every term the query builder emits carries FTS5's
-- prefix operator. Scripts that write without spaces therefore cannot be searched by any interior
-- fragment: a Korean or Japanese title is one token, so a reader who remembers the middle of a name
-- and not its beginning gets no results at all. The same limit applies to Latin text run together.
--
-- FTS5's `trigram` tokeniser indexes every three-character window instead of every word, which is
-- what makes an interior match possible. It cannot replace `unicode61` here:
--
--   * it cannot satisfy a query shorter than three characters, so swapping the tokeniser would turn
--     today's working one- and two-character prefix search into no matches at all, and
--   * indexing all of `summary` (22 MiB against 4 MiB of titles) by trigram would cost far more than
--     the interior match is worth on prose nobody searches by fragment.
--
-- So this is a second, narrower index rather than a change to the first, and the read path ORs the
-- two together. Word search keeps working exactly as it did, interior matches are new, and a query
-- too short for trigram simply falls back to the prefix behaviour it already had.

-- Title text only. `catalog_book_search_source` and `catalog_series_search_source` already compose
-- the same string, but they carry five correlated `group_concat` subqueries for the fields this index
-- does not hold, and a trigger that rebuilds one row evaluates all of them. V31 measured that shape
-- at 43 minutes of CPU across 111,745 books inside a single write transaction; a title-only view
-- keeps one subquery instead of five so maintaining this index does not re-inflate that cost.
CREATE VIEW catalog_book_title_source AS
SELECT
  b.id AS entity_id,
  trim(
    b.name || ' ' ||
    bm.title || ' ' ||
    s.name || ' ' ||
    sm.title || ' ' ||
    sm.title_sort || ' ' ||
    coalesce((
      SELECT group_concat(alternate.title, ' ')
      FROM series_metadata_alternate_title alternate
      WHERE alternate.series_id = s.id
    ), '')
  ) AS title
FROM book b
JOIN book_metadata bm ON bm.book_id = b.id
JOIN series s ON s.id = b.series_id
JOIN series_metadata sm ON sm.series_id = s.id;

CREATE VIEW catalog_series_title_source AS
SELECT
  s.id AS entity_id,
  trim(
    s.name || ' ' ||
    sm.title || ' ' ||
    sm.title_sort || ' ' ||
    coalesce((
      SELECT group_concat(alternate.title, ' ')
      FROM series_metadata_alternate_title alternate
      WHERE alternate.series_id = s.id
    ), '')
  ) AS title
FROM series s
JOIN series_metadata sm ON sm.series_id = s.id;

-- `trigram` defaults to case-insensitive matching, which is what the prefix index already does.
CREATE VIRTUAL TABLE catalog_title_substring USING fts5(
  entity_type UNINDEXED,
  entity_id UNINDEXED,
  title,
  tokenize = 'trigram'
);

INSERT INTO catalog_title_substring (entity_type, entity_id, title)
SELECT 'BOOK', entity_id, title FROM catalog_book_title_source;

INSERT INTO catalog_title_substring (entity_type, entity_id, title)
SELECT 'SERIES', entity_id, title FROM catalog_series_title_source;

-- These mirror the `catalog_search_*` triggers rather than extending them. Rewriting those would mean
-- transcribing V22's and V31's bodies again to add one statement each, and a transcription error there
-- silently stops maintaining the index the reader actually searches.
CREATE TRIGGER catalog_title_book_insert
AFTER INSERT ON book
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'BOOK' AND entity_id = NEW.id;
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'BOOK', entity_id, title FROM catalog_book_title_source WHERE entity_id = NEW.id;
END;

-- Guarded on a value actually changing, for V31's reason: SQLite fires `AFTER UPDATE OF <column>` on
-- the column appearing in the SET clause, and a library re-scan writes every matched book.
CREATE TRIGGER catalog_title_book_update
AFTER UPDATE OF name, series_id ON book
WHEN NEW.name IS NOT OLD.name OR NEW.series_id IS NOT OLD.series_id
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'BOOK' AND entity_id = NEW.id;
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'BOOK', entity_id, title FROM catalog_book_title_source WHERE entity_id = NEW.id;
END;

CREATE TRIGGER catalog_title_book_delete
AFTER DELETE ON book
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'BOOK' AND entity_id = OLD.id;
END;

CREATE TRIGGER catalog_title_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'BOOK' AND entity_id = NEW.book_id;
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'BOOK', entity_id, title FROM catalog_book_title_source WHERE entity_id = NEW.book_id;
END;

CREATE TRIGGER catalog_title_series_insert
AFTER INSERT ON series
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'SERIES' AND entity_id = NEW.id;
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'SERIES', entity_id, title FROM catalog_series_title_source WHERE entity_id = NEW.id;
END;

CREATE TRIGGER catalog_title_series_metadata_insert
AFTER INSERT ON series_metadata
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'SERIES' AND entity_id = NEW.series_id;
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'SERIES', entity_id, title FROM catalog_series_title_source WHERE entity_id = NEW.series_id;
END;

-- A series' name is part of every one of its books' indexed titles, so renaming it has to rebuild
-- them too. Same guard, same reason: unguarded, one no-op write costs the whole series.
CREATE TRIGGER catalog_title_series_update
AFTER UPDATE OF name ON series
WHEN NEW.name IS NOT OLD.name
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'SERIES' AND entity_id = NEW.id;
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'SERIES', entity_id, title FROM catalog_series_title_source WHERE entity_id = NEW.id;
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'BOOK' AND entity_id IN (SELECT id FROM book WHERE series_id = NEW.id);
  INSERT INTO catalog_title_substring (entity_type, entity_id, title)
  SELECT 'BOOK', entity_id, title
  FROM catalog_book_title_source
  WHERE entity_id IN (SELECT id FROM book WHERE series_id = NEW.id);
END;

CREATE TRIGGER catalog_title_series_delete
AFTER DELETE ON series
BEGIN
  DELETE FROM catalog_title_substring
  WHERE entity_type = 'SERIES' AND entity_id = OLD.id;
END;
