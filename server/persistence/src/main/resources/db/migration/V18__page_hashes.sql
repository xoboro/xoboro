CREATE TABLE page_hash_known (
  hash              TEXT    NOT NULL PRIMARY KEY,
  file_size         INTEGER,
  action            TEXT    NOT NULL,
  delete_count      INTEGER NOT NULL DEFAULT 0,
  created_at_ms     INTEGER NOT NULL,
  updated_at_ms     INTEGER NOT NULL,
  CONSTRAINT page_hash_size_non_negative
    CHECK (file_size IS NULL OR file_size >= 0),
  CONSTRAINT page_hash_action_valid
    CHECK (action IN ('DELETE_AUTO', 'DELETE_MANUAL', 'IGNORE')),
  CONSTRAINT page_hash_delete_count_non_negative CHECK (delete_count >= 0),
  CONSTRAINT page_hash_updated_after_created CHECK (updated_at_ms >= created_at_ms)
);

CREATE INDEX book_page_file_hash_idx
  ON book_page (file_hash, file_size, book_id, number)
  WHERE file_hash <> '';

CREATE INDEX book_file_hash_idx
  ON book (file_hash, file_size, id)
  WHERE file_hash <> '';
