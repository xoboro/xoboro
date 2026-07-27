CREATE TABLE series_book_metadata_aggregation (
  series_id       TEXT    NOT NULL PRIMARY KEY,
  summary         TEXT    NOT NULL DEFAULT '',
  summary_number  TEXT    NOT NULL DEFAULT '',
  release_date    TEXT,
  created_at_ms   INTEGER NOT NULL,
  updated_at_ms   INTEGER NOT NULL,
  FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE,
  CHECK (release_date IS NULL OR release_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
  CHECK (created_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE INDEX series_book_metadata_aggregation_release_idx
  ON series_book_metadata_aggregation(release_date);

CREATE TABLE series_book_metadata_aggregation_author (
  series_id  TEXT    NOT NULL,
  ordinal    INTEGER NOT NULL,
  name       TEXT    NOT NULL,
  role       TEXT    NOT NULL,
  PRIMARY KEY (series_id, ordinal),
  FOREIGN KEY (series_id)
    REFERENCES series_book_metadata_aggregation(series_id)
    ON DELETE CASCADE
);

CREATE INDEX series_book_metadata_aggregation_author_lookup_idx
  ON series_book_metadata_aggregation_author(name, role, series_id);

CREATE TABLE series_book_metadata_aggregation_tag (
  series_id  TEXT NOT NULL,
  tag        TEXT NOT NULL,
  PRIMARY KEY (series_id, tag),
  FOREIGN KEY (series_id)
    REFERENCES series_book_metadata_aggregation(series_id)
    ON DELETE CASCADE
);

CREATE INDEX series_book_metadata_aggregation_tag_lookup_idx
  ON series_book_metadata_aggregation_tag(tag, series_id);

CREATE TABLE series_book_metadata_aggregation_dirty (
  series_id  TEXT NOT NULL PRIMARY KEY,
  FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE
);

INSERT INTO series_book_metadata_aggregation_dirty(series_id)
SELECT id FROM series;

CREATE TRIGGER mark_series_aggregation_dirty_after_series_insert
AFTER INSERT ON series
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  VALUES (NEW.id)
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_book_insert
AFTER INSERT ON book
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  VALUES (NEW.series_id)
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_book_delete
AFTER DELETE ON book
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT OLD.series_id
  WHERE EXISTS (SELECT 1 FROM series WHERE id = OLD.series_id)
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_book_update
AFTER UPDATE OF series_id, relative_uri, deleted_at_ms, created_at_ms, updated_at_ms ON book
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT OLD.series_id
  WHERE EXISTS (SELECT 1 FROM series WHERE id = OLD.series_id)
  ON CONFLICT(series_id) DO NOTHING;
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  VALUES (NEW.series_id)
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT series_id FROM book WHERE id = NEW.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_book_metadata_update
AFTER UPDATE ON book_metadata
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT series_id FROM book WHERE id = NEW.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_book_metadata_delete
AFTER DELETE ON book_metadata
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT series_id FROM book WHERE id = OLD.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_author_insert
AFTER INSERT ON book_metadata_author
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT book.series_id
  FROM book
  WHERE book.id = NEW.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_author_update
AFTER UPDATE ON book_metadata_author
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT book.series_id
  FROM book
  WHERE book.id IN (OLD.book_id, NEW.book_id)
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_author_delete
AFTER DELETE ON book_metadata_author
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT book.series_id
  FROM book
  WHERE book.id = OLD.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_tag_insert
AFTER INSERT ON book_metadata_tag
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT book.series_id
  FROM book
  WHERE book.id = NEW.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_tag_update
AFTER UPDATE ON book_metadata_tag
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT book.series_id
  FROM book
  WHERE book.id IN (OLD.book_id, NEW.book_id)
  ON CONFLICT(series_id) DO NOTHING;
END;

CREATE TRIGGER mark_series_aggregation_dirty_after_tag_delete
AFTER DELETE ON book_metadata_tag
BEGIN
  INSERT INTO series_book_metadata_aggregation_dirty(series_id)
  SELECT book.series_id
  FROM book
  WHERE book.id = OLD.book_id
  ON CONFLICT(series_id) DO NOTHING;
END;
