ALTER TABLE series ADD COLUMN source_item_id TEXT NOT NULL DEFAULT '';
ALTER TABLE series ADD COLUMN file_modified_ms INTEGER NOT NULL DEFAULT 0;
ALTER TABLE series ADD COLUMN book_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE series ADD COLUMN deleted_at_ms INTEGER;
ALTER TABLE series ADD COLUMN oneshot INTEGER NOT NULL DEFAULT 0;

UPDATE series SET source_item_id = relative_uri WHERE source_item_id = '';

ALTER TABLE book ADD COLUMN source_item_id TEXT NOT NULL DEFAULT '';
ALTER TABLE book ADD COLUMN source_identity TEXT;
ALTER TABLE book ADD COLUMN file_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE book ADD COLUMN file_hash_koreader TEXT NOT NULL DEFAULT '';
ALTER TABLE book ADD COLUMN number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE book ADD COLUMN deleted_at_ms INTEGER;
ALTER TABLE book ADD COLUMN oneshot INTEGER NOT NULL DEFAULT 0;

UPDATE book SET source_item_id = relative_uri WHERE source_item_id = '';

CREATE INDEX book_library_source_identity_idx
  ON book (library_id, source_identity)
  WHERE source_identity IS NOT NULL;
CREATE INDEX book_library_deleted_idx
  ON book (library_id, deleted_at_ms);
CREATE INDEX series_library_deleted_idx
  ON series (library_id, deleted_at_ms);
