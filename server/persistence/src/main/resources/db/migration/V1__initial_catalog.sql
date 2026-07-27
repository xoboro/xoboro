CREATE TABLE library (
  id                TEXT    NOT NULL PRIMARY KEY,
  name              TEXT    NOT NULL,
  root_uri          TEXT    NOT NULL,
  created_at_ms     INTEGER NOT NULL,
  updated_at_ms     INTEGER NOT NULL,
  CONSTRAINT library_name_not_blank CHECK (length(trim(name)) > 0),
  CONSTRAINT library_root_uri_not_blank CHECK (length(trim(root_uri)) > 0),
  CONSTRAINT library_root_uri_unique UNIQUE (root_uri)
);

CREATE TABLE series (
  id                TEXT    NOT NULL PRIMARY KEY,
  library_id        TEXT    NOT NULL,
  relative_uri      TEXT    NOT NULL,
  name              TEXT    NOT NULL,
  sort_title        TEXT    NOT NULL,
  created_at_ms     INTEGER NOT NULL,
  updated_at_ms     INTEGER NOT NULL,
  CONSTRAINT series_library_fk
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
  CONSTRAINT series_name_not_blank CHECK (length(trim(name)) > 0),
  CONSTRAINT series_relative_uri_not_blank CHECK (length(trim(relative_uri)) > 0),
  CONSTRAINT series_location_unique UNIQUE (library_id, relative_uri)
);

CREATE INDEX series_library_id_idx ON series (library_id);
CREATE INDEX series_sort_title_idx ON series (sort_title);

CREATE TABLE book (
  id                TEXT    NOT NULL PRIMARY KEY,
  library_id        TEXT    NOT NULL,
  series_id         TEXT    NOT NULL,
  relative_uri      TEXT    NOT NULL,
  name              TEXT    NOT NULL,
  media_kind        TEXT    NOT NULL,
  file_size         INTEGER NOT NULL,
  file_modified_ms  INTEGER NOT NULL,
  created_at_ms     INTEGER NOT NULL,
  updated_at_ms     INTEGER NOT NULL,
  CONSTRAINT book_library_fk
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
  CONSTRAINT book_series_fk
    FOREIGN KEY (series_id) REFERENCES series (id) ON DELETE CASCADE,
  CONSTRAINT book_name_not_blank CHECK (length(trim(name)) > 0),
  CONSTRAINT book_relative_uri_not_blank CHECK (length(trim(relative_uri)) > 0),
  CONSTRAINT book_media_kind_valid
    CHECK (media_kind IN ('COMIC_ARCHIVE', 'PDF', 'EPUB')),
  CONSTRAINT book_file_size_non_negative CHECK (file_size >= 0),
  CONSTRAINT book_location_unique UNIQUE (library_id, relative_uri)
);

CREATE INDEX book_library_id_idx ON book (library_id);
CREATE INDEX book_series_id_idx ON book (series_id);
