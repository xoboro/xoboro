CREATE VIRTUAL TABLE catalog_search_fts USING fts5(
  entity_type UNINDEXED,
  entity_id UNINDEXED,
  title,
  summary,
  contributors,
  labels,
  identifiers,
  tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIEW catalog_book_search_source AS
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
  ) AS title,
  trim(bm.summary || ' ' || sm.summary) AS summary,
  coalesce((
    SELECT group_concat(author.name || ' ' || author.role, ' ')
    FROM book_metadata_author author
    WHERE author.book_id = b.id
  ), '') AS contributors,
  trim(
    sm.publisher || ' ' ||
    sm.language || ' ' ||
    coalesce((
      SELECT group_concat(book_tag.tag, ' ')
      FROM book_metadata_tag book_tag
      WHERE book_tag.book_id = b.id
    ), '') || ' ' ||
    coalesce((
      SELECT group_concat(series_tag.tag, ' ')
      FROM series_metadata_tag series_tag
      WHERE series_tag.series_id = s.id
    ), '') || ' ' ||
    coalesce((
      SELECT group_concat(genre.genre, ' ')
      FROM series_metadata_genre genre
      WHERE genre.series_id = s.id
    ), '')
  ) AS labels,
  trim(
    bm.isbn || ' ' ||
    coalesce((
      SELECT group_concat(link.label || ' ' || link.url, ' ')
      FROM book_metadata_link link
      WHERE link.book_id = b.id
    ), '')
  ) AS identifiers
FROM book b
JOIN book_metadata bm ON bm.book_id = b.id
JOIN series s ON s.id = b.series_id
JOIN series_metadata sm ON sm.series_id = s.id;

CREATE VIEW catalog_series_search_source AS
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
  ) AS title,
  sm.summary AS summary,
  coalesce((
    SELECT group_concat(author_value, ' ')
    FROM (
      SELECT DISTINCT author.name || ' ' || author.role AS author_value
      FROM book child
      JOIN book_metadata_author author ON author.book_id = child.id
      WHERE child.series_id = s.id
      ORDER BY author_value
    )
  ), '') AS contributors,
  trim(
    sm.publisher || ' ' ||
    sm.language || ' ' ||
    coalesce((
      SELECT group_concat(series_tag.tag, ' ')
      FROM series_metadata_tag series_tag
      WHERE series_tag.series_id = s.id
    ), '') || ' ' ||
    coalesce((
      SELECT group_concat(genre.genre, ' ')
      FROM series_metadata_genre genre
      WHERE genre.series_id = s.id
    ), '')
  ) AS labels,
  coalesce((
    SELECT group_concat(link.label || ' ' || link.url, ' ')
    FROM series_metadata_link link
    WHERE link.series_id = s.id
  ), '') AS identifiers
FROM series s
JOIN series_metadata sm ON sm.series_id = s.id;

INSERT INTO catalog_search_fts (
  entity_type, entity_id, title, summary, contributors, labels, identifiers
)
SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
FROM catalog_book_search_source;

INSERT INTO catalog_search_fts (
  entity_type, entity_id, title, summary, contributors, labels, identifiers
)
SELECT 'SERIES', entity_id, title, summary, contributors, labels, identifiers
FROM catalog_series_search_source;

CREATE TRIGGER catalog_search_book_insert
AFTER INSERT ON book
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

CREATE TRIGGER catalog_search_book_update
AFTER UPDATE OF name, series_id ON book
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

CREATE TRIGGER catalog_search_book_delete
AFTER DELETE ON book
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'BOOK' AND entity_id = OLD.id;
END;

CREATE TRIGGER catalog_search_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'BOOK' AND entity_id = NEW.book_id;
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'BOOK', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_book_search_source
  WHERE entity_id = NEW.book_id;
END;

CREATE TRIGGER catalog_search_series_insert
AFTER INSERT ON series
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'SERIES' AND entity_id = NEW.id;
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'SERIES', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_series_search_source
  WHERE entity_id = NEW.id;
END;

CREATE TRIGGER catalog_search_series_metadata_insert
AFTER INSERT ON series_metadata
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'SERIES' AND entity_id = NEW.series_id;
  INSERT INTO catalog_search_fts (
    entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT 'SERIES', entity_id, title, summary, contributors, labels, identifiers
  FROM catalog_series_search_source
  WHERE entity_id = NEW.series_id;
END;

CREATE TRIGGER catalog_search_series_update
AFTER UPDATE OF name ON series
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

CREATE TRIGGER catalog_search_series_delete
AFTER DELETE ON series
BEGIN
  DELETE FROM catalog_search_fts
  WHERE entity_type = 'SERIES' AND entity_id = OLD.id;
END;
