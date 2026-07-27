CREATE TABLE series_collection (
  id             TEXT    NOT NULL PRIMARY KEY,
  name           TEXT    NOT NULL COLLATE NOCASE UNIQUE,
  ordered        INTEGER NOT NULL,
  created_at_ms  INTEGER NOT NULL,
  updated_at_ms  INTEGER NOT NULL,
  CHECK (length(trim(name)) > 0),
  CHECK (ordered IN (0, 1)),
  CHECK (created_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE TABLE series_collection_member (
  collection_id  TEXT    NOT NULL,
  series_id      TEXT    NOT NULL,
  position       INTEGER NOT NULL,
  PRIMARY KEY (collection_id, series_id),
  UNIQUE (collection_id, position),
  FOREIGN KEY (collection_id) REFERENCES series_collection(id) ON DELETE CASCADE,
  FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE,
  CHECK (position >= 0)
);

CREATE INDEX series_collection_member_series_idx
  ON series_collection_member(series_id, collection_id);

CREATE TABLE read_list (
  id             TEXT    NOT NULL PRIMARY KEY,
  name           TEXT    NOT NULL COLLATE NOCASE UNIQUE,
  summary        TEXT    NOT NULL DEFAULT '',
  ordered        INTEGER NOT NULL,
  created_at_ms  INTEGER NOT NULL,
  updated_at_ms  INTEGER NOT NULL,
  CHECK (length(trim(name)) > 0),
  CHECK (ordered IN (0, 1)),
  CHECK (created_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE TABLE read_list_member (
  read_list_id  TEXT    NOT NULL,
  book_id       TEXT    NOT NULL,
  position      INTEGER NOT NULL,
  PRIMARY KEY (read_list_id, book_id),
  UNIQUE (read_list_id, position),
  FOREIGN KEY (read_list_id) REFERENCES read_list(id) ON DELETE CASCADE,
  FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE,
  CHECK (position >= 0)
);

CREATE INDEX read_list_member_book_idx
  ON read_list_member(book_id, read_list_id);
