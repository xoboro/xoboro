-- Adds EPUB_COVER to media_file.kind: the manifest item analysis identified as the OPF-declared
-- cover image, distinct from an ordinary EPUB_ASSET so cover generation can find it without
-- re-parsing the archive. SQLite has no ALTER TABLE for an existing CHECK constraint, so the table
-- is rebuilt with the widened constraint and its rows copied across unchanged.
CREATE TABLE media_file_new (
  book_id       TEXT    NOT NULL,
  number        INTEGER NOT NULL,
  file_name     TEXT    NOT NULL,
  media_type    TEXT,
  file_size     INTEGER,
  kind          TEXT    NOT NULL DEFAULT 'GENERAL',
  PRIMARY KEY (book_id, file_name),
  UNIQUE (book_id, number),
  CONSTRAINT media_file_book_fk
    FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
  CONSTRAINT media_file_size_non_negative CHECK (file_size IS NULL OR file_size >= 0),
  CONSTRAINT media_file_number_positive CHECK (number > 0),
  CONSTRAINT media_file_kind_valid
    CHECK (kind IN ('GENERAL', 'EPUB_PAGE', 'EPUB_ASSET', 'EPUB_COVER'))
);

INSERT INTO media_file_new (book_id, number, file_name, media_type, file_size, kind)
  SELECT book_id, number, file_name, media_type, file_size, kind FROM media_file;

DROP TABLE media_file;

ALTER TABLE media_file_new RENAME TO media_file;
