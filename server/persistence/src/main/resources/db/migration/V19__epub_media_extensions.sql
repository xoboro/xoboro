ALTER TABLE media
  ADD COLUMN epub_divina_compatible INTEGER NOT NULL DEFAULT 0
    CHECK (epub_divina_compatible IN (0, 1));

ALTER TABLE media
  ADD COLUMN epub_is_kepub INTEGER NOT NULL DEFAULT 0
    CHECK (epub_is_kepub IN (0, 1));

ALTER TABLE media
  ADD COLUMN epub_is_fixed_layout INTEGER NOT NULL DEFAULT 0
    CHECK (epub_is_fixed_layout IN (0, 1));

ALTER TABLE media_file
  ADD COLUMN kind TEXT NOT NULL DEFAULT 'GENERAL'
    CHECK (kind IN ('GENERAL', 'EPUB_PAGE', 'EPUB_ASSET'));

CREATE TABLE media_position (
  book_id             TEXT    NOT NULL,
  position            INTEGER NOT NULL,
  href                TEXT    NOT NULL,
  media_type          TEXT    NOT NULL,
  progression         REAL    NOT NULL,
  total_progression   REAL    NOT NULL,
  kobo_span           TEXT,
  PRIMARY KEY (book_id, position),
  CONSTRAINT media_position_book_fk
    FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
  CONSTRAINT media_position_number_positive CHECK (position > 0),
  CONSTRAINT media_position_progression_valid
    CHECK (progression >= 0 AND progression <= 1),
  CONSTRAINT media_position_total_progression_valid
    CHECK (total_progression >= 0 AND total_progression <= 1)
);

CREATE TABLE media_navigation_entry (
  book_id             TEXT NOT NULL,
  navigation_type     TEXT NOT NULL,
  path                TEXT NOT NULL,
  parent_path         TEXT,
  title               TEXT NOT NULL,
  href                TEXT,
  PRIMARY KEY (book_id, navigation_type, path),
  CONSTRAINT media_navigation_book_fk
    FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
  CONSTRAINT media_navigation_type_valid
    CHECK (navigation_type IN ('TOC', 'LANDMARK', 'PAGE_LIST'))
);

CREATE INDEX media_navigation_parent_idx
  ON media_navigation_entry (book_id, navigation_type, parent_path, path);
