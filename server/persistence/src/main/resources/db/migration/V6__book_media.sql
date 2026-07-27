CREATE TABLE media (
  book_id         TEXT    NOT NULL PRIMARY KEY,
  status          TEXT    NOT NULL,
  media_type      TEXT,
  profile         TEXT,
  page_count      INTEGER NOT NULL DEFAULT 0,
  comment         TEXT,
  created_at_ms   INTEGER NOT NULL,
  updated_at_ms   INTEGER NOT NULL,
  CONSTRAINT media_book_fk
    FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
  CONSTRAINT media_status_valid
    CHECK (status IN ('UNKNOWN', 'ERROR', 'READY', 'UNSUPPORTED', 'OUTDATED')),
  CONSTRAINT media_profile_valid
    CHECK (profile IS NULL OR profile IN ('DIVINA', 'PDF', 'EPUB')),
  CONSTRAINT media_page_count_non_negative CHECK (page_count >= 0),
  CONSTRAINT media_updated_after_created CHECK (updated_at_ms >= created_at_ms)
);

CREATE TABLE book_page (
  book_id       TEXT    NOT NULL,
  number        INTEGER NOT NULL,
  file_name     TEXT    NOT NULL,
  media_type    TEXT    NOT NULL,
  file_size     INTEGER,
  width         INTEGER,
  height        INTEGER,
  file_hash     TEXT    NOT NULL DEFAULT '',
  PRIMARY KEY (book_id, number),
  CONSTRAINT book_page_book_fk
    FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
  CONSTRAINT book_page_number_positive CHECK (number > 0),
  CONSTRAINT book_page_size_non_negative CHECK (file_size IS NULL OR file_size >= 0),
  CONSTRAINT book_page_dimensions_valid CHECK (
    (width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
  )
);

CREATE TABLE media_file (
  book_id       TEXT    NOT NULL,
  number        INTEGER NOT NULL,
  file_name     TEXT    NOT NULL,
  media_type    TEXT,
  file_size     INTEGER,
  PRIMARY KEY (book_id, file_name),
  UNIQUE (book_id, number),
  CONSTRAINT media_file_book_fk
    FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
  CONSTRAINT media_file_size_non_negative CHECK (file_size IS NULL OR file_size >= 0),
  CONSTRAINT media_file_number_positive CHECK (number > 0)
);
